package io.jclaw.contracts.turn;

/**
 * Durable transcript message identity, minted by the thread service.
 */
public record MessageId(String value) implements Ident {

    public MessageId {
        value = Ident.validate(value, "MessageId");
    }

    /** Mints a fresh, unique MessageId. */
    public static MessageId fresh() {
        return new MessageId(Ident.fresh("msg"));
    }

    @Override
    public String toString() {
        return value;
    }
}
