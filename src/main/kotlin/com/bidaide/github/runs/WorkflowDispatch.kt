package com.bidaide.github.runs

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.bidaide.gh.GhCli
import com.bidaide.gh.GhCommands
import com.bidaide.gh.GhException
import com.bidaide.gh.GhJson
import com.bidaide.gh.GhRepoContext
import com.bidaide.gh.GhWorkflow
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import org.yaml.snakeyaml.Yaml
import javax.swing.JComponent
import javax.swing.Timer

/** One `workflow_dispatch` input as declared in the workflow file. */
data class WorkflowInput(
    val name: String,
    val description: String,
    val required: Boolean,
    val default: String,
    /** string, boolean, choice, number or environment. */
    val type: String,
    val options: List<String>,
)

object WorkflowDispatch {

    /**
     * Inputs of the `workflow_dispatch` trigger, or null if the workflow cannot be started manually.
     * YAML 1.1 reads the bare key `on` as boolean true, so both spellings are looked up.
     */
    fun parseInputs(yaml: String): List<WorkflowInput>? {
        val root = runCatching { Yaml().load<Any?>(yaml) }.getOrNull() as? Map<*, *> ?: return null
        val dispatch = when (val on = root["on"] ?: root[true]) {
            is String -> if (on == TRIGGER) emptyMap<Any, Any>() else return null
            is List<*> -> if (TRIGGER in on) emptyMap<Any, Any>() else return null
            is Map<*, *> -> if (on.containsKey(TRIGGER)) on[TRIGGER] as? Map<*, *> ?: emptyMap<Any, Any>() else return null
            else -> return null
        }
        val inputs = dispatch["inputs"] as? Map<*, *> ?: return emptyList()
        return inputs.map { (name, raw) ->
            val spec = raw as? Map<*, *> ?: emptyMap<Any, Any>()
            WorkflowInput(
                name = name.toString(),
                description = spec["description"]?.toString().orEmpty(),
                required = spec["required"] == true,
                default = spec["default"]?.toString().orEmpty(),
                type = spec["type"]?.toString() ?: "string",
                options = (spec["options"] as? List<*>).orEmpty().map { it.toString() },
            )
        }
    }

    /** Lets the user pick a workflow, fill its inputs and start it. */
    fun start(project: Project, onStarted: () -> Unit) {
        GhCli.async(
            project, BidaideBundle.message("github.workflow.loading"),
            work = { GhJson.workflows(GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.workflowList())).filter { it.state == "active" } },
        ) { workflows ->
            if (workflows.isEmpty()) {
                Notifier.warn(project, "GitHub Actions", BidaideBundle.message("github.workflow.none"))
                return@async
            }
            JBPopupFactory.getInstance().createPopupChooserBuilder(workflows)
                .setTitle(BidaideBundle.message("github.workflow.choose"))
                .setRenderer(SimpleListCellRenderer.create("") { "${it.name}  (${it.path.substringAfterLast('/')})" })
                .setNamerForFiltering { it.name }
                .setItemChosenCallback { configure(project, it, onStarted) }
                .createPopup()
                .showCenteredInCurrentWindow(project)
        }
    }

    private fun configure(project: Project, workflow: GhWorkflow, onStarted: () -> Unit) {
        GhCli.async(
            project, BidaideBundle.message("github.workflow.reading", workflow.name),
            work = {
                parseInputs(GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.workflowYaml(workflow.id)))
                    ?: throw GhException(BidaideBundle.message("github.workflow.noDispatch", workflow.name))
            },
        ) { inputs ->
            val branch = GhRepoContext.repository(project)?.currentBranchName.orEmpty()
            val dialog = DispatchDialog(project, workflow, branch, inputs)
            if (!dialog.showAndGet()) return@async
            GhCli.async(
                project, BidaideBundle.message("github.workflow.running", workflow.name),
                work = { GhCli.run(GhRepoContext.requireWorkDir(project), GhCommands.workflowRun(workflow.id, dialog.ref, dialog.values)) },
            ) {
                Notifier.info(project, "GitHub Actions", BidaideBundle.message("github.workflow.started", workflow.name, dialog.ref.ifBlank { BidaideBundle.message("github.workflow.defaultBranch") }))
                // GitHub takes a moment to create the run.
                Timer(RUN_APPEARS_MILLIS) { onStarted() }.apply { isRepeats = false }.start()
            }
        }
    }

    private const val TRIGGER = "workflow_dispatch"
    private const val RUN_APPEARS_MILLIS = 4_000
}

private class DispatchDialog(
    project: Project,
    workflow: GhWorkflow,
    branch: String,
    private val inputs: List<WorkflowInput>,
) : DialogWrapper(project) {
    private val refField = JBTextField(branch, 24)
    private val editors: Map<WorkflowInput, JComponent> = inputs.associateWith { input ->
        when {
            input.type == "boolean" -> JBCheckBox().apply { isSelected = input.default.equals("true", ignoreCase = true) }
            input.type == "choice" && input.options.isNotEmpty() -> ComboBox(input.options.toTypedArray()).apply {
                if (input.default in input.options) selectedItem = input.default
            }
            else -> JBTextField(input.default)
        }
    }

    val ref: String get() = refField.text.trim()

    /** Blank optional text inputs are left out so the workflow's own defaults apply. */
    val values: Map<String, String>
        get() = buildMap {
            for ((input, editor) in editors) {
                val value = valueOf(editor)
                if (value.isNotEmpty() || editor !is JBTextField) put(input.name, value)
            }
        }

    init {
        title = BidaideBundle.message("github.workflow.dialogTitle", workflow.name)
        setOKButtonText(BidaideBundle.message("github.workflow.run"))
        init()
    }

    private fun valueOf(editor: JComponent): String = when (editor) {
        is JBCheckBox -> editor.isSelected.toString()
        is ComboBox<*> -> editor.selectedItem?.toString().orEmpty()
        is JBTextField -> editor.text.trim()
        else -> ""
    }

    override fun createCenterPanel(): JComponent = panel {
        row(BidaideBundle.message("github.workflow.ref")) { cell(refField).comment(BidaideBundle.message("github.workflow.ref.comment")) }
        if (inputs.isEmpty()) row { label(BidaideBundle.message("github.workflow.noInputs")) }
        for ((input, editor) in editors) {
            row("${input.name}${if (input.required) " *" else ""}:") {
                val cell = cell(editor)
                if (editor is JBTextField) cell.align(AlignX.FILL)
                if (input.description.isNotBlank()) cell.comment(input.description)
            }
        }
    }

    override fun doValidate(): ValidationInfo? =
        editors.entries.firstOrNull { (input, editor) -> input.required && valueOf(editor).isEmpty() }
            ?.let { (input, editor) -> ValidationInfo(BidaideBundle.message("github.workflow.required", input.name), editor) }
}
