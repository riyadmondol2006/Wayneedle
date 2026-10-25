package app.wayneedle.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Replaces `Dispatchers.Main` (used by `viewModelScope`) with an eager test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val dispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Keeps a `WhileSubscribed` state flow active for the whole test so `.value` is always current.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.keepActive(flow: Flow<T>) {
    val scope: CoroutineScope = backgroundScope
    scope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { } }
}
