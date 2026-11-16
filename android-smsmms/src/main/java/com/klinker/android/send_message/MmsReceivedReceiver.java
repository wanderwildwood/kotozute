/*
 * Copyright (C) 2015 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.klinker.android.send_message;

/**
 * The action and extras of the broadcast that says the system has finished downloading an MMS.
 *
 * This was an abstract BroadcastReceiver that also persisted the message and acknowledged it
 * to the carrier over the library's own HTTP client. Nothing extended it: the app has its own
 * receiver and worker for that, which acknowledge through the system. Only the names both
 * sides still share are left.
 */
public final class MmsReceivedReceiver {
    public static final String MMS_RECEIVED = "com.klinker.android.messaging.MMS_RECEIVED";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_LOCATION_URL = "location_url";
    public static final String EXTRA_TRIGGER_PUSH = "trigger_push";
    public static final String EXTRA_URI = "notification_ind_uri";
    public static final String SUBSCRIPTION_ID = "subscription_id";

    private MmsReceivedReceiver() {
    }
}
