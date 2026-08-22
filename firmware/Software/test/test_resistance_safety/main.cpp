#include <unity.h>

void test_baseline_config_motion_limits();
void test_baseline_emergency_stop_forces_stop_before_disabling_output();
void test_contract_constants();
void test_hysteresis_requires_filter_horizon();
void test_hysteresis_releases_only_at_lower_threshold();
void test_moderate_load_ramps_within_positive_rates();
void test_fresh_load_decay_is_at_least_twice_the_rise_rate();
void test_invalid_telemetry_decays_at_least_twice_as_fast();
void test_caps_and_user_ceilings_dominate();
void test_user_ceiling_below_base_prevents_expansion();
void test_firmware_limits_below_base_prevent_expansion();
void test_fixed_shallow_endpoint_and_deep_expansion();
void test_streaming_envelope_remap();
void test_exhaustive_percent_inputs_are_bounded();
void test_invalid_range_never_expands();
void test_unsafe_motion_envelope_forces_zero_bonus();
void test_each_invalid_signal_state_forces_zero_bonus();
void test_overload_confirmation_disables_by_100ms();
void test_spike_does_not_latch();
void test_latch_persists_through_reboot();
void test_recovery_requires_ack_then_successful_home();
void test_latched_fault_rejects_every_motion_command();
void test_all_lifecycle_events_clear_arming();
void test_stale_reversal_saturation_and_disconnect_never_arm();
void test_homing_and_calibrated_envelope_are_non_bypassable();
void test_targets_must_stay_inside_both_endpoints();
void test_repeated_endpoint_pressure_stops_and_latches();
void test_deterministic_bounded_trace();

void setUp() {}
void tearDown() {}

int main(int, char**) {
    UNITY_BEGIN();
    RUN_TEST(test_baseline_config_motion_limits);
    RUN_TEST(test_baseline_emergency_stop_forces_stop_before_disabling_output);
    RUN_TEST(test_contract_constants);
    RUN_TEST(test_hysteresis_requires_filter_horizon);
    RUN_TEST(test_hysteresis_releases_only_at_lower_threshold);
    RUN_TEST(test_moderate_load_ramps_within_positive_rates);
    RUN_TEST(test_fresh_load_decay_is_at_least_twice_the_rise_rate);
    RUN_TEST(test_invalid_telemetry_decays_at_least_twice_as_fast);
    RUN_TEST(test_caps_and_user_ceilings_dominate);
    RUN_TEST(test_user_ceiling_below_base_prevents_expansion);
    RUN_TEST(test_firmware_limits_below_base_prevent_expansion);
    RUN_TEST(test_fixed_shallow_endpoint_and_deep_expansion);
    RUN_TEST(test_streaming_envelope_remap);
    RUN_TEST(test_exhaustive_percent_inputs_are_bounded);
    RUN_TEST(test_invalid_range_never_expands);
    RUN_TEST(test_unsafe_motion_envelope_forces_zero_bonus);
    RUN_TEST(test_each_invalid_signal_state_forces_zero_bonus);
    RUN_TEST(test_overload_confirmation_disables_by_100ms);
    RUN_TEST(test_spike_does_not_latch);
    RUN_TEST(test_latch_persists_through_reboot);
    RUN_TEST(test_recovery_requires_ack_then_successful_home);
    RUN_TEST(test_latched_fault_rejects_every_motion_command);
    RUN_TEST(test_all_lifecycle_events_clear_arming);
    RUN_TEST(test_stale_reversal_saturation_and_disconnect_never_arm);
    RUN_TEST(test_homing_and_calibrated_envelope_are_non_bypassable);
    RUN_TEST(test_targets_must_stay_inside_both_endpoints);
    RUN_TEST(test_repeated_endpoint_pressure_stops_and_latches);
    RUN_TEST(test_deterministic_bounded_trace);
    return UNITY_END();
}
