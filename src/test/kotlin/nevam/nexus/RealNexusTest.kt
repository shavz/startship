package nevam.nexus

import com.google.common.truth.Truth.assertThat
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import nevam.FAKE
import nevam.extensions.advanceTimeBy
import nevam.extensions.second
import nevam.extensions.seconds
import nevam.isInstanceOf
import nevam.nexus.StatusCheckState.Checking
import nevam.nexus.StatusCheckState.Done
import nevam.nexus.StatusCheckState.GaveUp
import nevam.nexus.StatusCheckState.RetryingIn
import nevam.nexus.StatusCheckState.WillRetry
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC

class RealNexusTest {

  private val api = FakeNexusApi()
  private val testScheduler = TestScheduler()
  private val config = NexusConfig.DEFAULT

  private val nexus = RealNexus(
      api = api,
      debugMode = false,
      config = config
  )

  init {
    RxJavaPlugins.setSingleSchedulerHandler { testScheduler }
  }

  @Test fun `poll status with gradual back-off`() {
    val repositoryId = "nicolascage"

    val statusValues = nexus
        .pollUntilClosed(repositoryId)
        .test()
        .values()
    assertThat(statusValues.last()).isInstanceOf<Checking>()

    api.repository.onNext(
        StagingProfileRepository.FAKE.copy(
            id = repositoryId,
            type = "open",
            isTransitioning = true
        )
    )
    assertThat(statusValues.last()).isInstanceOf<WillRetry>()

    testScheduler.advanceTimeBy(config.closedStatusCheck.initialRetryDelay)
    assertThat(statusValues).containsExactly(
        Checking,
        WillRetry,
        RetryingIn(secondsRemaining = 5),
        RetryingIn(secondsRemaining = 4),
        RetryingIn(secondsRemaining = 3),
        RetryingIn(secondsRemaining = 2),
        RetryingIn(secondsRemaining = 1)
    ).inOrder()

    testScheduler.advanceTimeBy(config.closedStatusCheck.initialRetryDelay + 4.seconds)
    assertThat(statusValues).containsExactly(
        Checking,
        WillRetry,
        RetryingIn(secondsRemaining = 5),
        RetryingIn(secondsRemaining = 4),
        RetryingIn(secondsRemaining = 3),
        RetryingIn(secondsRemaining = 2),
        RetryingIn(secondsRemaining = 1),
        Checking,
        WillRetry,
        RetryingIn(secondsRemaining = 7),
        RetryingIn(secondsRemaining = 6),
        RetryingIn(secondsRemaining = 5),
        RetryingIn(secondsRemaining = 4),
        RetryingIn(secondsRemaining = 3),
        RetryingIn(secondsRemaining = 2),
        RetryingIn(secondsRemaining = 1),
        Checking,
        WillRetry
    ).inOrder()
  }

  @Test fun `stop polling for status after expiry time`() {
    val repositoryId = "nicolascage"
    api.repository.onNext(
        StagingProfileRepository.FAKE.copy(
            id = repositoryId,
            type = "open",
            isTransitioning = true
        )
    )

    val statusValues = nexus
        .pollUntilClosed(repositoryId)
        .test()
        .values()

    assertThat(statusValues).containsExactly(Checking, WillRetry)

    testScheduler.advanceTimeBy(config.closedStatusCheck.giveUpAfter)
    assertThat(statusValues.last()).isInstanceOf<GaveUp>()
  }

  @Test fun `stop polling for status once repository is closed`() {
    val repositoryId = "nicolascage"
    api.repository.onNext(
        StagingProfileRepository.FAKE.copy(
            id = repositoryId,
            type = "open",
            isTransitioning = true
        )
    )

    val statusValues = nexus
        .pollUntilClosed(repositoryId)
        .test()
        .values()

    assertThat(statusValues).containsExactly(Checking, WillRetry)

    api.repository.onNext(
        StagingProfileRepository.FAKE.copy(
            id = repositoryId,
            type = "closed",
            isTransitioning = false
        )
    )
    testScheduler.advanceTimeBy(config.closedStatusCheck.initialRetryDelay + 1.second)
    assertThat(statusValues.last()).isInstanceOf<Done>()
  }

  @Test fun `parse relative timestamp`() {
    val nowTime = Instant.ofEpochMilli(1596505477212) // Tue Aug 04 01:44:37 UTC 2020

    with(StagingProfileRepository.FAKE.copy(updatedAtString = "Tue Aug 04 01:44:00 UTC 2020")) {
      val timestamp = timestampRelativeToNow(clock = Clock.fixed(nowTime, UTC))
      assertThat(timestamp).isEqualTo("37s ago")
    }

    with(StagingProfileRepository.FAKE.copy(updatedAtString = "Tue Aug 04 01:17:19 UTC 2020")) {
      val timestamp = timestampRelativeToNow(clock = Clock.fixed(nowTime, UTC))
      assertThat(timestamp).isEqualTo("27m 18s ago")
    }

    with(StagingProfileRepository.FAKE.copy(updatedAtString = "Mon Aug 03 14:17:19 UTC 2020")) {
      val timestamp = timestampRelativeToNow(clock = Clock.fixed(nowTime, UTC))
      assertThat(timestamp).isEqualTo("11h 27m ago")
    }

    with(StagingProfileRepository.FAKE.copy(updatedAtString = "Sun Aug 02 01:17:19 UTC 2020")) {
      val timestamp = timestampRelativeToNow(clock = Clock.fixed(nowTime, UTC))
      assertThat(timestamp).isEqualTo("27m 18s ago")
    }
  }
}
