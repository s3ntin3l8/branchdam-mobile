package com.branchdam.mobile

/**
 * Test application subclass that disables production side effects.
 *
 * Robolectric by default instantiates the `Application` class from
 * the manifest. Production's [BranchDamApplication] triggers
 * WorkManager scheduling and Go engine initialization in its
 * `onCreate`, which leads to `IllegalStateException` in unit tests
 * that try to initialize their own mock WorkManager or that run in
 * an environment without the gomobile AAR.
 *
 * This subclass overrides `onCreate` and does nothing, providing a
 * clean slate for unit tests. It is registered via
 * `android/app/src/test/resources/robolectric.properties`.
 */
class TestBranchDamApplication : BranchDamApplication() {
    override fun onCreate() {
        // Do NOT call super.onCreate() to avoid side effects like WorkManager scheduling
        // or background threads in unit tests.
    }
}
