package com.mobiledgex.matchingengine;

import com.google.common.eventbus.DeadEvent;
import com.google.common.eventbus.Subscribe;

import java.util.Map;
import java.util.Set;

import distributed_match_engine.AppClient;

public class EdgeEventsListener {
    @Subscribe
    public void ClientEdgeEventReceiver(AppClient.ClientEdgeEvent cee) {
        // Do stuff.
        Map<String, String> tm = cee.getTagsMap();
        String count = tm.get("count"); // Should be an internal HashMap with a Map interface.
        String bort = tm.get("foo");
        System.out.println("Count from server: " + count);
    }

    @Subscribe
    public void handleDeadEvent(DeadEvent deadEvent) {
        System.out.println("You have messages, but are not subscribed to events.");
    }
}
