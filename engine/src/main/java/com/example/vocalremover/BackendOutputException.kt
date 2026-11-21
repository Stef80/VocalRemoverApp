package com.example.vocalremover

/**
 * The backend ran without errors but produced an unusable chunk (non-finite values, or silence
 * from a non-silent input): treated like an ORT failure, so the next backend is tried.
 */
class BackendOutputException(message: String) : RuntimeException(message)
