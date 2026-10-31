#include <unity.h>

#include <cstring>

#include "../../src/structs/PatternInfo.h"

void setUp() {}
void tearDown() {}

void test_every_pattern_has_a_name() {
    TEST_ASSERT_EQUAL_INT(7, pattern_info::kCount);
    for (int i = 0; i < pattern_info::kCount; i++) {
        const char* name = pattern_info::kNames[i];
        TEST_ASSERT_NOT_NULL(name);
        TEST_ASSERT_TRUE(strlen(name) > 0);
    }
}

void test_names_follow_the_enum_order() {
    TEST_ASSERT_EQUAL_STRING("Simple Stroke",
                             pattern_info::nameOf(StrokePatterns::SimpleStroke));
    TEST_ASSERT_EQUAL_STRING(
        "Teasing Pounding",
        pattern_info::nameOf(StrokePatterns::TeasingPounding));
    TEST_ASSERT_EQUAL_STRING("Insist",
                             pattern_info::nameOf(StrokePatterns::Insist));
}

void test_names_are_safe_to_put_in_json() {
    for (int i = 0; i < pattern_info::kCount; i++) {
        TEST_ASSERT_NULL(strpbrk(pattern_info::kNames[i], "\"\\"));
    }
}

void test_wrap_index_stays_in_range() {
    TEST_ASSERT_EQUAL_INT(0, pattern_info::wrapIndex(0));
    TEST_ASSERT_EQUAL_INT(6, pattern_info::wrapIndex(6));
    TEST_ASSERT_EQUAL_INT(0, pattern_info::wrapIndex(7));
    TEST_ASSERT_EQUAL_INT(2, pattern_info::wrapIndex(100));
    TEST_ASSERT_EQUAL_INT(6, pattern_info::wrapIndex(-1));
}

void test_state_protocol_is_at_least_two() {
    TEST_ASSERT_TRUE(pattern_info::kStateProtocolVersion >= 2);
}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_every_pattern_has_a_name);
    RUN_TEST(test_names_follow_the_enum_order);
    RUN_TEST(test_names_are_safe_to_put_in_json);
    RUN_TEST(test_wrap_index_stays_in_range);
    RUN_TEST(test_state_protocol_is_at_least_two);
    return UNITY_END();
}
