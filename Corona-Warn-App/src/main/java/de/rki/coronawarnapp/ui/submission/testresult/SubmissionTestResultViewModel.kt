package de.rki.coronawarnapp.ui.submission.testresult

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import de.rki.coronawarnapp.exception.http.CwaWebException
import de.rki.coronawarnapp.nearby.ENFClient
import de.rki.coronawarnapp.notification.TestResultNotificationService
import de.rki.coronawarnapp.storage.LocalData
import de.rki.coronawarnapp.storage.SubmissionRepository
import de.rki.coronawarnapp.submission.Symptoms
import de.rki.coronawarnapp.ui.submission.viewmodel.SubmissionNavigationEvents
import de.rki.coronawarnapp.util.DeviceUIState
import de.rki.coronawarnapp.util.Event
import de.rki.coronawarnapp.util.coroutine.DispatcherProvider
import de.rki.coronawarnapp.util.ui.SingleLiveEvent
import de.rki.coronawarnapp.util.viewmodel.CWAViewModel
import de.rki.coronawarnapp.util.viewmodel.InjectedSubmissionViewModelFactory
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class SubmissionTestResultViewModel @AssistedInject constructor(
    dispatcherProvider: DispatcherProvider,
    private val enfClient: ENFClient,
    private val testResultNotificationService: TestResultNotificationService,
    @Assisted private val submissionRepository: SubmissionRepository
) : CWAViewModel(dispatcherProvider = dispatcherProvider) {

    val routeToScreen: SingleLiveEvent<SubmissionNavigationEvents> = SingleLiveEvent()
    val showTracingRequiredScreen = SingleLiveEvent<Unit>()
    val showRedeemedTokenWarning = SingleLiveEvent<Unit>()

    private var wasRedeemedTokenErrorShown = false
    private val tokenErrorMutex = Mutex()

    val uiState: LiveData<TestResultUIState> = combineTransform(
        submissionRepository.uiStateStateFlow,
        submissionRepository.deviceUIStateFlow,
        submissionRepository.testResultReceivedDateFlow
    ) { apiRequestState, deviceUiState, resultDate ->

        tokenErrorMutex.withLock {
            if (!wasRedeemedTokenErrorShown && deviceUiState == DeviceUIState.PAIRED_REDEEMED) {
                wasRedeemedTokenErrorShown = true
                showRedeemedTokenWarning.postValue(Unit)
            }
        }

        TestResultUIState(
            apiRequestState = apiRequestState,
            deviceUiState = deviceUiState,
            testResultReceivedDate = resultDate
        ).let { emit(it) }
    }.asLiveData(context = dispatcherProvider.Default)

    suspend fun observeTestResultToSchedulePositiveTestResultReminder() =
        submissionRepository.deviceUIStateFlow
            .first { it == DeviceUIState.PAIRED_POSITIVE || it == DeviceUIState.PAIRED_POSITIVE_TELETAN }
            .also { testResultNotificationService.schedulePositiveTestResultReminder() }

    val uiStateError: LiveData<Event<CwaWebException>> = submissionRepository.uiStateError

    fun onBackPressed() {
        routeToScreen.postValue(SubmissionNavigationEvents.NavigateToMainActivity)
    }

    fun onContinuePressed() {
        Timber.d("onContinuePressed()")
        requireTracingOrShowError {
            routeToScreen.postValue(SubmissionNavigationEvents.NavigateToSymptomIntroduction)
        }
    }

    fun onContinueWithoutSymptoms() {
        Timber.d("onContinueWithoutSymptoms()")
        requireTracingOrShowError {
            Symptoms.NO_INFO_GIVEN
                .let { SubmissionNavigationEvents.NavigateToResultPositiveOtherWarning(it) }
                .let { routeToScreen.postValue(it) }
        }
    }

    private fun requireTracingOrShowError(action: () -> Unit) = launch {
        if (enfClient.isTracingEnabled.first()) {
            action()
        } else {
            showTracingRequiredScreen.postValue(Unit)
        }
    }

    fun deregisterTestFromDevice() {
        launch {
            Timber.d("deregisterTestFromDevice()")
            submissionRepository.deleteTestGUID()
            submissionRepository.deleteRegistrationToken()
            LocalData.isAllowedToSubmitDiagnosisKeys(false)
            LocalData.initialTestResultReceivedTimestamp(0L)

            routeToScreen.postValue(SubmissionNavigationEvents.NavigateToMainActivity)
        }
    }

    fun refreshDeviceUIState(refreshTestResult: Boolean = true) {
        submissionRepository.refreshDeviceUIState(refreshTestResult)
    }

    @AssistedInject.Factory
    interface Factory : InjectedSubmissionViewModelFactory<SubmissionTestResultViewModel>
}
