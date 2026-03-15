package uy.plomo.cloud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TunnelRequest(
        String name,
        @JsonProperty("src_addr") String srcAddr,
        @JsonProperty("src_port") String srcPort,
        @JsonProperty("dst_port") String dstPort,
        @JsonProperty("use_this_server") String useThisServer
) {
    public TunnelRequest {
        if (srcAddr == null || srcAddr.isBlank())  throw new IllegalArgumentException("src_addr is required");
        if (srcPort == null || srcPort.isBlank())  throw new IllegalArgumentException("src_port is required");
        if (dstPort == null || dstPort.isBlank())  throw new IllegalArgumentException("dst_port is required");
        if (name == null || name.isBlank())        name = "tunnel";
        if (useThisServer == null)                 useThisServer = "off";
    }

    public boolean usesThisServer() {
        return "on".equalsIgnoreCase(useThisServer);
    }
}
