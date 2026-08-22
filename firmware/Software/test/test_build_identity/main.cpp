#include <cstring>
#include <string>

#include <unity.h>

#ifndef FIRMWARE_TRACK
#define FIRMWARE_TRACK "test"
#endif

#ifndef FIRMWARE_BUILD_SHA
#define FIRMWARE_BUILD_SHA "0123456789abcdef0123456789abcdef01234567"
#endif

#include "constants/Version.h"

void setUp() {}
void tearDown() {}

void test_build_identity_combines_version_track_and_full_sha() {
    const std::string expected =
        std::string("OSSM_BUILD_IDENTITY=version=") + VERSION +
        ";track=" + FIRMWARE_TRACK + ";sha=" + FIRMWARE_BUILD_SHA;

    TEST_ASSERT_EQUAL_UINT32(40, std::strlen(FIRMWARE_BUILD_SHA));
    TEST_ASSERT_EQUAL_STRING(expected.c_str(), OSSM_BUILD_IDENTITY);
}

int main(int argc, char** argv) {
    UNITY_BEGIN();
    RUN_TEST(test_build_identity_combines_version_track_and_full_sha);
    return UNITY_END();
}
