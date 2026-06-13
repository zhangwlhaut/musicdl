package com.musicdl.car.ui

/**
 * Generic UI state for a single async resource. Screens use sealed pattern
 * matching to render loading / empty / error states uniformly.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>

    companion object {
        fun <T> fromResult(result: Result<T>): UiState<T> = result.fold(
            onSuccess = { Success(it) },
            onFailure = { Error(it.message ?: it::class.java.simpleName) }
        )
    }
}
