#include <unity.h>

#include "services/communication/ble_remote_control.h"

namespace {

void test_claim_requires_authenticated_connection() {
    ble_remote_control::Lease lease;
    TEST_ASSERT_FALSE(lease.claim(7, false, 100));
    TEST_ASSERT_FALSE(lease.active());
    TEST_ASSERT_TRUE(lease.claim(7, true, 100));
    TEST_ASSERT_TRUE(lease.active());
}

void test_only_owner_can_refresh_or_release_lease() {
    ble_remote_control::Lease lease;
    TEST_ASSERT_TRUE(lease.claim(7, true, 100));
    TEST_ASSERT_FALSE(lease.heartbeat(8, 120));
    TEST_ASSERT_FALSE(lease.active());

    TEST_ASSERT_TRUE(lease.claim(7, true, 200));
    TEST_ASSERT_FALSE(lease.release(8));
    TEST_ASSERT_TRUE(lease.active());
    TEST_ASSERT_TRUE(lease.heartbeat(7, 300));
    TEST_ASSERT_TRUE(lease.release(7));
    TEST_ASSERT_FALSE(lease.active());
}

void test_watchdog_expires_at_250ms_including_timer_rollover() {
    ble_remote_control::Lease lease;
    TEST_ASSERT_TRUE(lease.claim(7, true, 100));
    TEST_ASSERT_FALSE(lease.expired(349));
    TEST_ASSERT_TRUE(lease.expired(350));

    TEST_ASSERT_TRUE(lease.claim(7, true, 0xfffffff0U));
    TEST_ASSERT_FALSE(lease.expired(0x000000e9U));
    TEST_ASSERT_TRUE(lease.expired(0x000000eaU));
}

void test_owner_disconnect_revokes_lease() {
    ble_remote_control::Lease lease;
    TEST_ASSERT_TRUE(lease.claim(7, true, 100));
    TEST_ASSERT_FALSE(lease.disconnected(8));
    TEST_ASSERT_TRUE(lease.active());
    TEST_ASSERT_TRUE(lease.disconnected(7));
    TEST_ASSERT_FALSE(lease.active());
}

}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_claim_requires_authenticated_connection);
    RUN_TEST(test_only_owner_can_refresh_or_release_lease);
    RUN_TEST(test_watchdog_expires_at_250ms_including_timer_rollover);
    RUN_TEST(test_owner_disconnect_revokes_lease);
    return UNITY_END();
}
