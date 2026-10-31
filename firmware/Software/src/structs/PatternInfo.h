#ifndef SOFTWARE_PATTERNINFO_H
#define SOFTWARE_PATTERNINFO_H

#include "SettingPercents.h"

// Description of the stroke-engine patterns that the phone can read from the
// state notification, so the app does not have to hard-code the list.
// Pure data, no hardware dependency: covered by native tests.
namespace pattern_info {

// Version of the state notification. 1 = original fields; 2 adds "protocol",
// "patternName" and "patternCount". Fields are only ever added, never removed
// or renamed, so older apps keep working.
constexpr int kStateProtocolVersion = 2;

// Names in StrokePatterns order. No quote or backslash: they go into JSON as is.
constexpr const char* kNames[] = {
    "Simple Stroke", "Teasing Pounding", "Robo Stroke", "Half n Half",
    "Deeper",        "Stop n Go",        "Insist",
};

constexpr int kCount = sizeof(kNames) / sizeof(kNames[0]);

// Adding a pattern to the enum without naming it here stops the build.
static_assert(static_cast<int>(StrokePatterns::Insist) == kCount - 1,
              "pattern_info::kNames must list every StrokePatterns value");

inline int indexOf(StrokePatterns pattern) {
    const int index = static_cast<int>(pattern);
    return (index >= 0 && index < kCount) ? index : 0;
}

inline const char* nameOf(StrokePatterns pattern) {
    return kNames[indexOf(pattern)];
}

// Maps any number received from the phone onto a valid pattern index.
inline int wrapIndex(int value) {
    const int wrapped = value % kCount;
    return wrapped < 0 ? wrapped + kCount : wrapped;
}

}  // namespace pattern_info

#endif  // SOFTWARE_PATTERNINFO_H
