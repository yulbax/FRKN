package io.github.yulbax.frkn.data.profile

sealed interface ProfileOperationResult {
    data class Success(val affected: Int = 1) : ProfileOperationResult
    data object InvalidLink : ProfileOperationResult
    data object EmptySubscription : ProfileOperationResult
    data class FetchFailed(val cause: Throwable) : ProfileOperationResult
}
