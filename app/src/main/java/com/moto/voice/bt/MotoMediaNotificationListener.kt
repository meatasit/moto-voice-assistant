package com.moto.voice.bt

import android.service.notification.NotificationListenerService

/**
 * v1.3.11 §1.1 — presence-only NotificationListenerService.
 *
 * Android requires a bound [NotificationListenerService] before an app can call
 * [android.media.session.MediaSessionManager.getActiveSessions] to enumerate other
 * apps' MediaControllers. We don't actually consume notifications — we override
 * NOTHING beyond the class declaration — but the manifest entry + user granting
 * "Notification access" in Settings is what unlocks the media-session API.
 *
 * The rider-facing rationale (surfaced in [com.moto.voice.SystemStatusActivity]
 * and [com.moto.voice.OnboardingActivity] as spec-required Thai copy):
 *   "อนุญาตให้ Moto Voice อ่านสถานะเพลง/วิดีโอ และควบคุมการเล่น (เล่น/หยุด/เลื่อน)".
 *
 * Optional — every code path that consults [com.moto.voice.media.MediaSessions]
 * falls back to media-key dispatch when the permission is denied so no feature
 * breaks; the rider just loses the "เล่นแล้วค่ะ" confirmation and precise seek.
 */
class MotoMediaNotificationListener : NotificationListenerService()
