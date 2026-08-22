#include <algorithm>
#include <fstream>
#include <sstream>
#include <string>

#include <unity.h>

#include "constants/Config.h"

namespace {

std::string readActionsSource() {
    std::string sourcePath = __FILE__;
    std::replace(sourcePath.begin(), sourcePath.end(), '\\', '/');
    const std::string marker = "test/test_resistance_safety/test_baseline.cpp";
    const auto markerAt = sourcePath.rfind(marker);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, markerAt);
    sourcePath.replace(markerAt, marker.size(), "src/ossm/state/actions.cpp");

    std::ifstream source(sourcePath);
    TEST_ASSERT_TRUE_MESSAGE(source.good(), sourcePath.c_str());
    std::ostringstream contents;
    contents << source.rdbuf();
    return contents.str();
}

}

void test_baseline_config_motion_limits() {
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 1500.0f, Config::Driver::maxRPM);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 50000.0f, Config::Driver::maxAcceleration);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 6.0f, Config::Driver::sensorlessCurrentLimit);
    TEST_ASSERT_FLOAT_WITHIN(0.001f, 10000.0f, Config::Driver::maxStrokeSteps);
}

void test_baseline_emergency_stop_forces_stop_before_disabling_output() {
    const std::string source = readActionsSource();
    const auto emergencyStop = source.find("void ossmEmergencyStop()");
    const auto forceStop = source.find("stepper->forceStop();", emergencyStop);
    const auto disableOutputs = source.find("stepper->disableOutputs();", emergencyStop);

    TEST_ASSERT_NOT_EQUAL(std::string::npos, emergencyStop);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, forceStop);
    TEST_ASSERT_NOT_EQUAL(std::string::npos, disableOutputs);
    TEST_ASSERT_LESS_THAN(disableOutputs, forceStop);
}
