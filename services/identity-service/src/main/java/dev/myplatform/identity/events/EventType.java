package dev.myplatform.identity.events;

/**
 * An event type and payload version, e.g. {@code identity.user.registered} v1.
 * The topic is {@code <type>.v<version>} (ADR 0010), so a breaking change gets a new topic.
 */
record EventType(String name, int version) {

    static final EventType USER_REGISTERED = new EventType("identity.user.registered", 1);

    String topic() {
        return name + ".v" + version;
    }

}
