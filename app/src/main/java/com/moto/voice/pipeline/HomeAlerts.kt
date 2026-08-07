package com.moto.voice.pipeline

/**
 * v1.3.35 — which [StatusRow]s deserve space on the HOME screen.
 *
 * Rider report (7 Aug 2026): after an APK update the "เปิดสื่อตอนจอล็อค"
 * (USE_FULL_SCREEN_INTENT) grant is sometimes gone, and the only place that says so is
 * [com.moto.voice.SystemStatusActivity] — buried two taps deep behind Settings. The
 * rider finds out while riding, when a locked "เปิดกรรมกรข่าว" silently fails. Home has
 * to surface it the moment the app opens.
 *
 * The home panel is deliberately NOT the full status page:
 *
 *  * **Only actionable, sticky settings.** A row earns a home slot when the rider can
 *    fix it once in OS settings and it then stays fixed. That is exactly the set below.
 *  * **[StatusRow.Kind.Helmet] and [StatusRow.Kind.Internet] are excluded** — both are
 *    Yellow/Red during perfectly normal use (helmet off, phone indoors), so they would
 *    park a permanent warning on home and train the rider to ignore the panel. The
 *    status banner already carries "ออฟไลน์", and the full page still shows both.
 *  * **[StatusRow.Kind.Webhook] and [StatusRow.Kind.Tts] are excluded** — they are the
 *    async checks. Home renders from [SystemStatusChecker.checkSync] only, so it paints
 *    instantly and never fires a webhook round-trip just because the app was opened.
 *
 * Green (and Pending) never appear: "ถ้ามีแล้วก็ไม่ต้องแสดง". When nothing qualifies the
 * whole section is hidden, so a healthy install shows history instead of a wall of ✓.
 */
object HomeAlerts {

    /**
     * Rider-fixable settings that survive across upgrades — and therefore can silently
     * break during one. Keep this list tight; every addition costs home-screen space.
     */
    private val ACTIONABLE: Set<StatusRow.Kind> = setOf(
        StatusRow.Kind.DefaultAssistant,   // no assistant role = the BVRA button does nothing
        StatusRow.Kind.Permissions,        // mic/contacts/call/bluetooth
        StatusRow.Kind.LockScreenLaunch,   // the one that goes missing after an update
        StatusRow.Kind.MediaCtrl,          // notification listener — optional but degrades seek/confirm
        StatusRow.Kind.Battery,            // app killed while the screen is off
    )

    /**
     * Pure predicate — kept free of [StatusRow] itself so JVM tests can exercise every
     * kind/state pair without constructing an `android.content.Intent`.
     */
    fun isAlert(kind: StatusRow.Kind, state: StatusRow.State): Boolean =
        kind in ACTIONABLE &&
            (state == StatusRow.State.Red || state == StatusRow.State.Yellow)

    /**
     * The rows home should show, in the order [SystemStatusChecker.checkSync] returned
     * them (Default Assistant first, then permissions — the order the rider fixes them in).
     */
    fun alerts(rows: List<StatusRow>): List<StatusRow> = rows.filter { isAlert(it.id, it.state) }
}
