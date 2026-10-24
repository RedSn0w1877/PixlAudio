package com.theveloper.pixelplay.data.ai.claude

/**
 * Claude could not answer and the caller asked to run its own on-device path instead of the
 * generic one (AiHandler.generateContent with onDeviceFallback = false): the playlist curator and
 * the lyrics translator have on-device paths sized for a 4k-token model.
 */
class CloudFallbackException(val claudeFailure: ClaudeException) :
    Exception(claudeFailure.message, claudeFailure)
