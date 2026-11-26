// IAgentService.aidl
package dev.tabdesk.agent;

import dev.tabdesk.agent.IAgentCallback;

interface IAgentService {
    /** The interface version: 1. */
    int version() = 1;

    /** TabDesk's state for this caller, as JSON (see status()). No side effects. */
    String status() = 2;

    /** Registers this caller's callback, replacing any earlier one. Call it once after binding, before send(). */
    void connect(IAgentCallback callback) = 3;

    /** One MCP JSON-RPC message. A request gets one reply or replyStream unless it's cancelled; a notification none. */
    oneway void send(String id, String json) = 4;

    /** Drops the request sent with this id if it's still running. No reply comes for it afterwards. */
    oneway void cancel(String id) = 5;

    /** The user pressed Stop in the calling app: drops every running request, and control is stopped. */
    oneway void stop() = 6;

    /** The user sent the next message in the chat: control may go on after a Stop or a pause. */
    oneway void resume() = 7;
}
