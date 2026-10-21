// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// The one place that decides which core the app runs on. The shipped app uses
// `LiveKetchCore`. The UI smoke test sets `KETCH_USE_FAKE_CORE=1` because it
// asserts the sample packages, and a hosted unit-test run launches this
// process as TEST_HOST: the fake keeps that launch off the network.

import Foundation

enum CoreFactory {
    static func make(root: URL = KetchRoot.resolve()) -> any KetchCoreProtocol {
        let env = ProcessInfo.processInfo.environment
        if env["KETCH_USE_FAKE_CORE"] == "1" || env["XCTestConfigurationFilePath"] != nil {
            return FakeKetchCore(root: root, stepDelay: .milliseconds(250))
        }
        return LiveKetchCore(root: root)
    }
}
