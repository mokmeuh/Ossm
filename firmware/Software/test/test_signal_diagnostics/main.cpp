#include "signal_diagnostics.h"
#include "unity.h"

void test_duplicate_millis_and_backlog_keep_scheduled_100hz_timestamps() {
    SignalDiagnosticAcquisition acquisition;

    const SignalDiagnosticSample first = acquisition.capture(
        1000, 2048, 512, -1, 12000, 24000, -3200);
    const SignalDiagnosticSample duplicateMillis = acquisition.capture(
        1000, 5000, 512, 1, 13000, 25000, -3000);
    const SignalDiagnosticSample backlog = acquisition.capture(
        1090, 1024, 512, 0, 0, 0, -3000);
    const SignalDiagnosticSample duplicateBacklogMillis = acquisition.capture(
        1090, 1024, 512, 0, 0, 0, -3000);

    TEST_ASSERT_EQUAL_UINT32(1000, first.timestampMs);
    TEST_ASSERT_EQUAL_UINT32(1010, duplicateMillis.timestampMs);
    TEST_ASSERT_EQUAL_UINT32(1020, backlog.timestampMs);
    TEST_ASSERT_EQUAL_UINT32(1030, duplicateBacklogMillis.timestampMs);
    TEST_ASSERT_EQUAL_UINT16(4095, duplicateMillis.rawAdc);
    TEST_ASSERT_EQUAL_UINT16(512, duplicateMillis.offsetAdc);
    TEST_ASSERT_EQUAL_INT8(1, duplicateMillis.direction);
    TEST_ASSERT_EQUAL_INT32(13000, duplicateMillis.commandedSpeedMilliHz);
    TEST_ASSERT_EQUAL_UINT32(25000, duplicateMillis.commandedAcceleration);
    TEST_ASSERT_EQUAL_INT32(-3000, duplicateMillis.commandedPosition);
    TEST_ASSERT_TRUE(duplicateMillis.saturated);
    TEST_ASSERT_FALSE(first.saturated);
    TEST_ASSERT_TRUE(duplicateMillis.timestampMonotonic);
    TEST_ASSERT_TRUE(backlog.timestampMonotonic);
    TEST_ASSERT_TRUE(duplicateBacklogMillis.timestampMonotonic);
}

int main() {
    UNITY_BEGIN();
    RUN_TEST(test_duplicate_millis_and_backlog_keep_scheduled_100hz_timestamps);
    return UNITY_END();
}
