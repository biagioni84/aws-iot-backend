package uy.plomo.cloud.controllers;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uy.plomo.cloud.services.GatewayEventBroadcaster;

@RestController
@PreAuthorize("hasRole('USER')")
public class EventController {

    private final GatewayEventBroadcaster broadcaster;

    public EventController(GatewayEventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(value = "/api/v1/{gwId}/events", produces = "text/event-stream")
    public SseEmitter subscribe(@PathVariable String gwId) {
        return broadcaster.subscribe(gwId);
    }
}
