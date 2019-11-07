package nevam.nexus

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.output.TermUi.echo
import io.reactivex.Observable
import io.reactivex.Single
import nevam.extensions.Observables
import nevam.extensions.executeAsResult
import nevam.extensions.mapToResult
import nevam.extensions.second
import nevam.extensions.seconds
import nevam.extensions.stacktraceToString
import nevam.nexus.ApiResult.Failure
import nevam.nexus.ApiResult.Failure.Type.Network
import nevam.nexus.ApiResult.Failure.Type.Server
import nevam.nexus.ApiResult.Failure.Type.UserAuth
import nevam.nexus.ApiResult.Success
import nevam.nexus.StagingProfileRepository.Status
import nevam.nexus.StagingProfileRepository.Status.Closed
import nevam.nexus.StagingProfileRepository.Status.Open
import nevam.nexus.StagingProfileRepository.Status.Transitioning
import nevam.nexus.StagingProfileRepository.Status.Unknown
import nevam.nexus.StatusCheckState.Checking
import nevam.nexus.StatusCheckState.Done
import nevam.nexus.StatusCheckState.GaveUp
import nevam.nexus.StatusCheckState.RetryingIn
import nevam.nexus.StatusCheckState.WillRetry
import java.time.Duration

class RealNexus(
  private val api: NexusApi,
  private val debugMode: Boolean
) : Nexus {

  @Throws(CliktError::class)
  override fun stagingRepositories(): List<StagingProfileRepository> {
    return when (val result = api.stagingRepositories().executeAsResult()) {
      is Success -> result.response!!.repositories
      is Failure -> when (result.type) {
        UserAuth -> throw invalidCredentialsError()
        else -> throw genericApiError(result)
      }
    }
  }

  override fun close(repository: StagingProfileRepository) {
    val request = CloseStagingRepositoryRequest(repositoryId = repository.id)

    when (val result = api.close(repository.profileId, request).executeAsResult()) {
      is Failure -> when (result.type) {
        UserAuth -> throw invalidCredentialsError()
        else -> throw genericApiError(result)
      }
    }
  }

  override fun pollUntilClosed(repositoryId: RepositoryId, giveUpAfter: Duration): Observable<StatusCheckState> {
    val giveUpAfterTimer = Observables
        .timer(giveUpAfter)
        .map { GaveUp(it) }

    var nextRetryDelaySeconds = 5
    val increaseDelay = { nextRetryDelaySeconds = (nextRetryDelaySeconds * 1.5).toInt() }

    return api.repository(repositoryId)
        .mapToResult()
        .map {
          when (it) {
            is Success -> when (val status = it.response!!.status) {
              is Closed -> Done
              is Transitioning -> WillRetry
              is Open -> throw CliktError("Repository is still open! :/")
              is Unknown -> throw CliktError("Received an unexpected status: ${status.displayValue}")
            }
            is Failure -> when (it.type) {
              Network, Server -> WillRetry
              UserAuth -> throw invalidCredentialsError()
              else -> throw genericApiError(it)
            }
          }
        }
        .toObservable()
        .startWith(Checking)
        .switchMap { status ->
          if (status == WillRetry) {
            Observables.interval(1.second)
                .map<StatusCheckState> { RetryingIn(nextRetryDelaySeconds - it.seconds) }
                .startWith(WillRetry)
                // Adding +1 to timer because a gap of 5 second means retrying on the 6th second.
                .takeUntil(Observables.timer((nextRetryDelaySeconds + 1).seconds))
                .doOnComplete { increaseDelay() }

          } else {
            Observable.just(status)
          }
        }
        .repeat()
        .mergeWith(giveUpAfterTimer)
        .takeUntil { it is Done || it is GaveUp }
  }

  private fun genericApiError(result: Failure): CliktError {
    if (debugMode) echo(result.error.stacktraceToString())
    return CliktError("Failed to connect to nexus (${result.error})")
  }

  private fun invalidCredentialsError() =
    CliktError("Nexus refused your user credentials. Double-check that your username and password are correct?")
}