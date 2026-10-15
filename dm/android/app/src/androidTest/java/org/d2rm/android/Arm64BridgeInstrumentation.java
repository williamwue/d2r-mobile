// 校验 JNI 合成桥接报告，明确区分通过、失败和不支持的 ABI。
// Validate JNI synthetic bridge reports, distinguishing passed, failed, and unsupported ABIs.
package org.d2rm.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

final class Arm64BridgeInstrumentation {
    static void run(Instrumentation instrumentation) {
        Bundle result = new Bundle();
        try {
            String raw = NativeProbe.runArm64BridgeSuite();
            result.putString("bridgeReport", raw);
            result.putString("stream", "\nARM64_BRIDGE_REPORT=" + raw + "\n");
            JSONObject report = new JSONObject(raw);
            String status = report.getString("status");
            int executed = report.getInt("executedChecks");
            JSONArray checks = report.getJSONArray("checks");
            if (status.equals("unsupported_abi")) {
                if (executed != 0 || report.getString("compiledAbi").equals("arm64-v8a"))
                    throw new AssertionError("Unsupported report executed ARM64 checks");
                for (int i = 0; i < checks.length(); i++)
                    if (!checks.getJSONObject(i).getString("status").equals("not_run"))
                        throw new AssertionError("Unsupported check did not report not_run");
            } else {
                if (!status.equals("passed") || executed == 0)
                    throw new AssertionError(raw);
                for (int i = 0; i < checks.length(); i++)
                    if (!checks.getJSONObject(i).getString("status").equals("passed"))
                        throw new AssertionError(raw);
            }
            instrumentation.finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", result.getString("stream", "") + "\nFAIL ARM64 bridge\n" + android.util.Log.getStackTraceString(failure));
            instrumentation.finish(Activity.RESULT_CANCELED, result);
        }
    }
}
