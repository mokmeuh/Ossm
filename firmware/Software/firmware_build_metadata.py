Import("env")

import os

build_sha = os.getenv("RELEASE_BUILD_SHA") or os.getenv("GITHUB_SHA")
if not build_sha:
    raise RuntimeError("RELEASE_BUILD_SHA or GITHUB_SHA is required")

env.Append(
    CPPDEFINES=[
        (
            "FIRMWARE_BUILD_SHA",
            env.StringifyMacro(build_sha),
        )
    ]
)
