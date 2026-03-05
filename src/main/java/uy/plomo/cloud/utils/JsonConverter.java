package uy.plomo.cloud.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;


import java.util.Map;

/**
 * Utility for JSON conversion using Jackson ObjectMapper.
 * Static methods for easy use without dependency injection.
 */
@Slf4j
public class JsonConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Private constructor - esta clase no debe instanciarse
    private JsonConverter() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Convert JSON string to Map<String, Object>
     *
     * @param jsonString JSON string to convert
     * @return Map representation of the JSON
     * @throws JsonConversionException if conversion fails
     */
    public static Map<String, Object> toMap(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string cannot be null or empty");
        }

        try {
            return objectMapper.readValue(
                    jsonString,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to convert JSON to Map: {}", jsonString, e);
            throw new JsonConversionException("Failed to parse JSON string", e);
        }
    }

    /**
     * Convert object to Map<String, Object>
     * Useful for converting POJOs or any object to a map representation
     *
     * @param object Object to convert
     * @return Map representation
     */
    public static Map<String, Object> objectToMap(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }

        try {
            return objectMapper.convertValue(
                    object,
                    new TypeReference<Map<String, Object>>() {}
            );
        } catch (IllegalArgumentException e) {
            log.error("Failed to convert object to Map: {}", object.getClass().getName(), e);
            throw new JsonConversionException("Failed to convert object to Map", e);
        }
    }

    /**
     * Convert Map to JSON string
     *
     * @param map Map to convert
     * @return JSON string representation
     */
    public static String mapToJson(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("Map cannot be null");
        }

        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert Map to JSON", e);
            throw new JsonConversionException("Failed to convert Map to JSON", e);
        }
    }

    /**
     * Convert object to JSON string
     *
     * @param object Object to convert
     * @return JSON string
     */
    public static String toJson(Object object) {
        if (object == null) {
            throw new IllegalArgumentException("Object cannot be null");
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert object to JSON: {}", object.getClass().getName(), e);
            throw new JsonConversionException("Failed to convert object to JSON", e);
        }
    }

    /**
     * Custom exception for JSON conversion errors
     */
    public static class JsonConversionException extends RuntimeException {
        public JsonConversionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}