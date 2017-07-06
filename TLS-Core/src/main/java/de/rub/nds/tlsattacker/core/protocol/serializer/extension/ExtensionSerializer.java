/**
 * TLS-Attacker - A Modular Penetration Testing Framework for TLS
 *
 * Copyright 2014-2017 Ruhr University Bochum / Hackmanit GmbH
 *
 * Licensed under Apache License 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package de.rub.nds.tlsattacker.core.protocol.serializer.extension;

import de.rub.nds.modifiablevariable.util.ArrayConverter;
import de.rub.nds.tlsattacker.core.constants.ExtensionByteLength;
import de.rub.nds.tlsattacker.core.protocol.message.extension.ExtensionMessage;
import de.rub.nds.tlsattacker.core.protocol.serializer.Serializer;

/**
 *
 * @author Robert Merget - robert.merget@rub.de
 * @param <T>
 */
public abstract class ExtensionSerializer<T extends ExtensionMessage> extends Serializer {

    private final ExtensionMessage msg;

    public ExtensionSerializer(T message) {
        super();
        this.msg = message;
    }

    @Override
    protected byte[] serializeBytes() {
        writeType();
        writeLength();
        serializeExtensionContent();

        return getAlreadySerialized();
    }

    private void writeType() {
        appendBytes(msg.getExtensionType().getValue());
        LOGGER.debug("ExtensionType: " + ArrayConverter.bytesToHexString(msg.getExtensionType().getValue()));
    }

    private void writeLength() {
        appendInt(msg.getExtensionLength().getValue(), ExtensionByteLength.EXTENSIONS_LENGTH);
        LOGGER.debug("extensionLength: " + msg.getExtensionLength().getValue());
    }

    public abstract byte[] serializeExtensionContent();
}