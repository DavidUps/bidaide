import ServiceManagement
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(SettingsKey.pollInterval) private var pollInterval = 120.0
    @AppStorage(SettingsKey.includeDrafts) private var includeDrafts = false
    @AppStorage(SettingsKey.showMineInMenu) private var showMine = true
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var launchError: String?

    private let intervals: [(TimeInterval, String)] = [
        (60, "1 minuto"), (120, "2 minutos"), (300, "5 minutos"), (600, "10 minutos"), (900, "15 minutos"),
    ]

    var body: some View {
        Form {
            Section("General") {
                Picker("Consultar GitHub cada", selection: $pollInterval) {
                    ForEach(intervals, id: \.0) { Text($0.1).tag($0.0) }
                }
                .onChange(of: pollInterval) { model.restartPolling() }
                Toggle("Incluir borradores al revisar", isOn: $includeDrafts)
                    .onChange(of: includeDrafts) { Task { await model.refresh() } }
                Toggle("Mostrar «Mis PR» en el menú", isOn: $showMine)
                Toggle("Abrir al iniciar sesión", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, enabled in setLaunchAtLogin(enabled) }
                if let launchError {
                    Text(launchError).font(.caption).foregroundStyle(.red)
                }
            }

            Section("Notificarme cuando…") {
                ForEach(NotificationReason.all) { reason in
                    NotificationToggle(reason: reason)
                }
                Button("Enviar notificación de prueba") {
                    Notifier.shared.post(
                        id: UUID().uuidString, title: "@octocat ha comentado",
                        subtitle: "acme/android#128 · Pantalla de pago", body: "¿Podemos extraer esto a un caso de uso?",
                        url: "https://github.com/notifications", threadID: nil
                    )
                }
            }

            Section("GitHub CLI") {
                LabeledContent("Binario", value: GhCli.executable?.path ?? "No encontrado")
                LabeledContent("Cuenta", value: model.snapshot.map { "@\($0.login)" } ?? "—")
                if let error = model.lastError {
                    Text(error).font(.caption).foregroundStyle(.red).textSelection(.enabled)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            launchError = nil
        } catch {
            launchError = error.localizedDescription
            launchAtLogin = SMAppService.mainApp.status == .enabled
        }
    }
}

private struct NotificationToggle: View {
    let reason: NotificationReason
    @AppStorage private var enabled: Bool

    init(reason: NotificationReason) {
        self.reason = reason
        _enabled = AppStorage(wrappedValue: reason.enabledByDefault, SettingsKey.notify(reason.id))
    }

    var body: some View {
        Toggle(reason.label, isOn: $enabled)
    }
}
