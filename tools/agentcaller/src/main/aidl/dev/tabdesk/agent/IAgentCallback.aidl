// IAgentCallback.aidl
package dev.tabdesk.agent;

oneway interface IAgentCallback {
    /** The answer to send(id, …), when it's at most 32 Ki characters. */
    void reply(String id, String json) = 1;

    /** Control changed: see "Control states". Also sent once straight after connect(). */
    void onControlState(String state, String reason) = 2;

    /** A bigger answer to send(id, …): read the pipe to its end, as UTF-8 JSON. */
    void replyStream(String id, in ParcelFileDescriptor pipe) = 3;
}
