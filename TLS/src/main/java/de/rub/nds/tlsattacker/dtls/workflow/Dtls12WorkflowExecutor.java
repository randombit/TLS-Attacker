/**
 * TLS-Attacker - A Modular Penetration Testing Framework for TLS.
 *
 * Copyright (C) 2015 Chair for Network and Data Security,
 *                    Ruhr University Bochum
 *                    (juraj.somorovsky@rub.de)
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
package de.rub.nds.tlsattacker.dtls.workflow;

import de.rub.nds.tlsattacker.dtls.protocol.handshake.handlers.HandshakeFragmentHandler;
import de.rub.nds.tlsattacker.dtls.protocol.handshake.messagefields.HandshakeMessageDtlsFields;
import de.rub.nds.tlsattacker.dtls.record.handlers.RecordHandler;
import de.rub.nds.tlsattacker.tls.constants.ConnectionEnd;
import de.rub.nds.tlsattacker.tls.exceptions.ConfigurationException;
import de.rub.nds.tlsattacker.tls.exceptions.WorkflowExecutionException;
import de.rub.nds.tlsattacker.tls.protocol.ProtocolMessage;
import de.rub.nds.tlsattacker.tls.protocol.ProtocolMessageHandler;
import de.rub.nds.tlsattacker.tls.protocol.alert.constants.AlertLevel;
import de.rub.nds.tlsattacker.tls.protocol.alert.messages.AlertMessage;
import de.rub.nds.tlsattacker.tls.protocol.constants.ProtocolMessageType;
import de.rub.nds.tlsattacker.dtls.record.messages.Record;
import de.rub.nds.tlsattacker.tls.protocol.handshake.messages.HandshakeMessage;
import de.rub.nds.tlsattacker.tls.workflow.GenericWorkflowExecutor;
import de.rub.nds.tlsattacker.tls.workflow.TlsContext;
import de.rub.nds.tlsattacker.tls.workflow.WorkflowTrace;
import de.rub.nds.tlsattacker.transport.TransportHandler;
import de.rub.nds.tlsattacker.util.ArrayConverter;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.Arrays;

/**
 * @author Florian Pfützenreuter <florian.pfuetzenreuter@rub.de>
 */
public class Dtls12WorkflowExecutor extends GenericWorkflowExecutor {

    private static final Logger LOGGER = LogManager.getLogger(Dtls12WorkflowExecutor.class);

    private boolean unexpectedMessageFound, allMessagesParsedPreviously = true;

    private byte[] recordContentBuffer = new byte[0], digestBytesBeforeNextFlightBegin = new byte[0],
	    handshakeMessageSendBuffer, recordSendBuffer = new byte[0], changeCipherSpecBytes = null;

    private int messageParseBufferOffset, flightStartMessageNumber, messageFlightPointer, sendHandshakeMessageSeq,
	    epochCounter, flightRetransmitCounter, maxWaitForExpectedRecord = 3000, maxFlightRetries = 4,
	    previousFlightBeginPointer = -1, maxPacketSize = 1400, maxHandshakeReorderBufferSize = 100;

    private final WorkflowTrace workflowTrace;

    private List<ProtocolMessage> protocolMessages;

    private Record currentRecord;

    private List<de.rub.nds.tlsattacker.tls.record.messages.Record> recordBuffer = new LinkedList<>(),
	    handshakeMessageSendRecordList = null;

    private ProtocolMessageType currentProtocolMessageType = ProtocolMessageType.ALERT;

    private ConnectionEnd lastConnectionEnd, previousFlightConnectionEnd;

    private final HandshakeFragmentHandler handshakeFragmentHandler = new HandshakeFragmentHandler();

    public Dtls12WorkflowExecutor(TransportHandler transportHandler, TlsContext tlsContext) {
	super(transportHandler, tlsContext);
	this.workflowTrace = this.tlsContext.getWorkflowTrace();
	tlsContext.setRecordHandler(new RecordHandler(tlsContext));
	recordHandler = tlsContext.getRecordHandler();
	if (this.transportHandler == null || recordHandler == null) {
	    throw new ConfigurationException("The WorkflowExecutor was not configured properly");
	}
    }

    @Override
    public void executeWorkflow() throws WorkflowExecutionException {
	if (executed) {
	    throw new IllegalStateException("The workflow has already been executed. Create a new Workflow.");
	}
	executed = true;

	lastConnectionEnd = null;
	protocolMessages = workflowTrace.getProtocolMessages();
	try {
	    ProtocolMessage pm;

	    while (workflowContext.getProtocolMessagePointer() < protocolMessages.size()
		    && workflowContext.isProceedWorkflow()) {
		pm = getNextWorkflowProtocolMessage();
		updateFlight(pm);
		if (pm.getMessageIssuer() == tlsContext.getMyConnectionEnd()) {
		    handleMyProtocolMessage(pm);
		} else {
		    receiveAndParseNextProtocolMessage(pm);
		}
	    }
	} catch (Exception e) {
	    e.printStackTrace();
	    workflowContext.decrementProtocolMessagePointer();
	    throw new WorkflowExecutionException(e.getLocalizedMessage(), e);
	} finally {
	    // remove all unused protocol messages
	    this.removeNextProtocolMessages(protocolMessages, workflowContext.getProtocolMessagePointer() - 1);
	}
    }

    private void handleMyProtocolMessage(ProtocolMessage pm) throws IOException {
	LOGGER.debug("Preparing the following protocol message to send: {}", pm.getClass());

	if (pm.getProtocolMessageType() == ProtocolMessageType.HANDSHAKE) {
	    handleMyHandshakeMessage((HandshakeMessage) pm);
	} else if (pm.getProtocolMessageType() == ProtocolMessageType.CHANGE_CIPHER_SPEC) {
	    handleMyChangeCipherSpecMessage(pm);
	} else {
	    handleMyNonHandshakeMessage(pm);
	}
    }

    private void handleMyNonHandshakeMessage(ProtocolMessage protocolMessage) throws IOException {
	byte[] messageBytes = protocolMessage.getCompleteResultingMessage().getValue();

	if (protocolMessage.getRecords() == null || protocolMessage.getRecords().isEmpty()) {
	    protocolMessage.addRecord(new Record());
	}

	byte[] record = recordHandler.wrapData(messageBytes, protocolMessage.getProtocolMessageType(),
		protocolMessage.getRecords());

	LOGGER.debug("Sending the following protocol message to TLS peer: {}\nRaw Bytes: {}",
		protocolMessage.getClass(), ArrayConverter.bytesToHexString(record));

	if (protocolMessage.getProtocolMessageType() == ProtocolMessageType.CHANGE_CIPHER_SPEC) {
	    sendMessages(record);
	} else {
	    transportHandler.sendData(record);
	}
    }

    private void handleMyChangeCipherSpecMessage(ProtocolMessage protocolMessage) throws IOException {
	ProtocolMessageHandler pmh = protocolMessage.getProtocolMessageHandler(tlsContext);
	byte[] messageBytes = pmh.prepareMessage();

	// retransmitBuffer = ArrayConverter.concatenate(retransmitBuffer,
	// messageBytes);

	if (protocolMessage.getRecords() == null || protocolMessage.getRecords().isEmpty()) {
	    protocolMessage.addRecord(new Record());
	}

	byte[] record = recordHandler.wrapData(messageBytes, ProtocolMessageType.CHANGE_CIPHER_SPEC,
		protocolMessage.getRecords());

	sendMessages(record);
    }

    private void handleMyHandshakeMessage(HandshakeMessage handshakeMessage) throws IOException {
	int maxMessageSize = maxPacketSize - 25;

	ProtocolMessageHandler pmh = handshakeMessage.getProtocolMessageHandler(tlsContext);
	HandshakeMessageDtlsFields handshakeMessageFields = (HandshakeMessageDtlsFields) handshakeMessage
		.getMessageFields();
	handshakeMessageFields.setMessageSeq(sendHandshakeMessageSeq);
	byte[] handshakeMessageBytes = pmh.prepareMessage();
	int handshakeMessageContentLength = handshakeMessageBytes.length - 12;

	if (handshakeMessageContentLength > maxMessageSize) {
	    byte[] handshakeMessageContentBytes = new byte[handshakeMessageContentLength];
	    System.arraycopy(handshakeMessageBytes, 12, handshakeMessageContentBytes, 0, handshakeMessageContentLength);
	    handshakeMessageSendBuffer = ArrayConverter.concatenate(
		    handshakeMessageSendBuffer,
		    prepareHandshakeMessageSend(handshakeMessageContentBytes, handshakeMessage
			    .getHandshakeMessageType().getValue(), sendHandshakeMessageSeq, maxMessageSize));
	} else {
	    handshakeMessageSendBuffer = ArrayConverter.concatenate(handshakeMessageSendBuffer, handshakeMessageBytes);
	}

	if (handshakeMessageSendRecordList == null) {
	    handshakeMessageSendRecordList = new ArrayList<>();
	    handshakeMessageSendRecordList.add(new Record());
	}

	handshakeMessage.setRecords(handshakeMessageSendRecordList);

	if (handlingMyLastProtocolMessageWithContentType(protocolMessages, workflowContext.getProtocolMessagePointer())) {
	    sendMessages(recordHandler.wrapData(handshakeMessageSendBuffer, ProtocolMessageType.HANDSHAKE,
		    handshakeMessage.getRecords()));
	    handshakeMessageSendRecordList = null;
	    handshakeMessageSendBuffer = new byte[0];
	}
    }

    private byte[] prepareHandshakeMessageSend(byte[] handshakeMessageBytes, byte handshakeMessageType,
	    int handshakeMessageSeq, int maxMessageSize) {
	maxMessageSize -= 12;
	int messageSize = handshakeMessageBytes.length;
	if (messageSize >= maxMessageSize) {
	    handshakeMessageBytes = handshakeFragmentHandler.fragmentHandshakeMessage(handshakeMessageBytes,
		    handshakeMessageType, handshakeMessageSeq, maxMessageSize);
	}
	return handshakeMessageBytes;
    }

    private void sendMessages(byte[] records) throws IOException {
	recordSendBuffer = ArrayConverter.concatenate(recordSendBuffer, records);
	if (handlingMyLastProtocolMessage(protocolMessages, workflowContext.getProtocolMessagePointer())) {
	    LOGGER.debug("Sending the following protocol messages to TLS peer: {}",
		    ArrayConverter.bytesToHexString(recordSendBuffer));
	    int pointer = 0;
	    int currentRecordSize = 0;
	    byte[] sendBuffer = new byte[0];

	    while (pointer < recordSendBuffer.length) {
		currentRecordSize = (recordSendBuffer[pointer + 11] << 8) + recordSendBuffer[pointer + 12] + 13;
		if ((sendBuffer.length + currentRecordSize) > maxPacketSize) {
		    transportHandler.sendData(sendBuffer);
		    sendBuffer = new byte[0];
		} else {
		    sendBuffer = ArrayConverter.concatenate(sendBuffer,
			    Arrays.copyOfRange(recordSendBuffer, pointer, pointer + currentRecordSize));
		    recordSendBuffer = Arrays.copyOfRange(recordSendBuffer, pointer + currentRecordSize,
			    recordSendBuffer.length);
		}
	    }
	    if (sendBuffer.length > 0) {
		transportHandler.sendData(sendBuffer);
	    }
	    recordSendBuffer = new byte[0];
	}
    }

    private byte[] getNextProtocolMessageBytes(ProtocolMessage pm) throws Exception {
	switch (pm.getProtocolMessageType()) {
	    case HANDSHAKE:
		return getHandshakeMessage();
	    case CHANGE_CIPHER_SPEC:
		return getChangeCipherSpecMessage();
	    default:
		return getNonHandshakeNonCcsMessages();
	}
    }

    private void receiveAndParseNextProtocolMessage(ProtocolMessage pm) throws Exception {
	byte[] rawMessageBytes = null;

	if (allMessagesParsedPreviously) {
	    rawMessageBytes = getNextProtocolMessageBytes(pm);
	}
	if (rawMessageBytes == null) {
	    if (flightRetransmitCounter >= maxFlightRetries) {
		workflowContext.setProceedWorkflow(false);
	    } else {
		abortFlight();
	    }
	}

	ProtocolMessageHandler pmh = currentProtocolMessageType.getProtocolMessageHandler(
		rawMessageBytes[messageParseBufferOffset], tlsContext);

	if (!pmh.isCorrectProtocolMessage(pm)) {
	    pm = wrongMessageFound(pmh);
	} else {
	    pmh.setProtocolMessage(pm);
	}

	messageParseBufferOffset = pmh.parseMessage(recordContentBuffer, messageParseBufferOffset);

	if (LOGGER.isDebugEnabled()) {
	    LOGGER.debug("The following message was parsed: {}", pmh.getProtocolMessage().toString());
	}

	if (pm.getProtocolMessageType() == ProtocolMessageType.ALERT) {
	    handleIncomingAlert(pmh);
	} else if (pm.getProtocolMessageType() == ProtocolMessageType.HANDSHAKE) {
	    handshakeFragmentHandler.addRecordsToHandshakeMessage(pm);
	    handshakeFragmentHandler.incrementExpectedHandshakeMessageSeq();
	} else {
	    pm.addRecord(currentRecord);
	}

	// workflowContext.incrementProtocolMessagePointer();
	allMessagesParsedPreviously = messageParseBufferOffset >= rawMessageBytes.length;
    }

    private void updateFlight(ProtocolMessage pm) {
	if (pm.getProtocolMessageType() == ProtocolMessageType.HANDSHAKE
		|| pm.getProtocolMessageType() == ProtocolMessageType.CHANGE_CIPHER_SPEC) {
	    if (lastConnectionEnd != pm.getMessageIssuer()) {
		previousFlightBeginPointer = flightStartMessageNumber;
		previousFlightConnectionEnd = lastConnectionEnd;
		flightStartMessageNumber = workflowContext.getProtocolMessagePointer();
		digestBytesBeforeNextFlightBegin = tlsContext.getDigest().getRawBytes();
		flightRetransmitCounter = 0;
	    }
	    messageFlightPointer = workflowContext.getProtocolMessagePointer();
	    lastConnectionEnd = pm.getMessageIssuer();
	}
    }

    private void abortFlight() {
	// handshakeMessageRecordMap.clear();
	// handshakeMessageReassembleBitmaskMap.clear();
	// reassembledHandshakeMessageMap.clear();
	allMessagesParsedPreviously = true;
	flightStartMessageNumber = previousFlightBeginPointer;
	workflowContext.setProtocolMessagePointer(flightStartMessageNumber);
	tlsContext.getDigest().setRawBytes(digestBytesBeforeNextFlightBegin);
	flightRetransmitCounter++;
    }

    private ProtocolMessage getNextWorkflowProtocolMessage() {
	ProtocolMessage pm = getCurrentWorkflowProtocolMessage(workflowContext.getProtocolMessagePointer());
	workflowContext.incrementProtocolMessagePointer();
	return pm;
    }

    private ProtocolMessage getCurrentWorkflowProtocolMessage(int messagePointer) {
	if (messagePointer < protocolMessages.size()) {
	    return protocolMessages.get(messagePointer);
	}
	return null;
    }

    private boolean handleIncomingAlert(ProtocolMessageHandler pmh) {
	AlertMessage am = (AlertMessage) pmh.getProtocolMessage();
	am.setMessageIssuer(ConnectionEnd.SERVER);
	if (AlertLevel.getAlertLevel(am.getLevel().getValue()) == AlertLevel.FATAL) {
	    LOGGER.debug("The workflow execution is stopped because of a FATAL error");
	    return false;
	}
	return true;
    }

    private ProtocolMessage wrongMessageFound(ProtocolMessageHandler pmh) {
	LOGGER.debug("The configured protocol message is not equal to the message being parsed or the message was not found.");
	removeNextProtocolMessages(protocolMessages, workflowContext.getProtocolMessagePointer());
	pmh.initializeProtocolMessage();
	ProtocolMessage pm = pmh.getProtocolMessage();
	protocolMessages.add(pm);
	unexpectedMessageFound = true;
	return pm;
    }

    protected byte[] getHandshakeMessage() throws Exception {
	Record rcvRecord;
	ProtocolMessageType rcvRecordProtocolMessageType = null;
	long endTimeMillies = System.currentTimeMillis() + maxWaitForExpectedRecord;
	boolean messageAvailable = false;
	byte[] rawMessageBytes;

	while (!messageAvailable && System.currentTimeMillis() <= endTimeMillies) {
	    rawMessageBytes = handshakeFragmentHandler.getHandshakeMessage();
	    if (rawMessageBytes != null) {
		return rawMessageBytes;
	    }
	    try {
		rcvRecord = receiveNextValidRecord();
	    } catch (SocketTimeoutException ste) {
		continue;
	    }
	    rcvRecordProtocolMessageType = ProtocolMessageType.getContentType(rcvRecord.getContentType().getValue());
	    switch (rcvRecordProtocolMessageType) {
		case ALERT:
		    return rcvRecord.getProtocolMessageBytes().getValue();
		case HANDSHAKE:
		    handshakeFragmentHandler.processHandshakeRecord(rcvRecord);
		    break;
		case CHANGE_CIPHER_SPEC:
		    processChangeCipherSpecRecord(rcvRecord);
		    break;
		default:
		    break;
	    }
	}
	return null;
    }

    protected byte[] getNonHandshakeNonCcsMessages() throws Exception {
	Record rcvRecord;
	ProtocolMessageType rcvRecordProtocolMessageType = null;
	long endTimeMillies = System.currentTimeMillis() + maxWaitForExpectedRecord;

	while ((rcvRecordProtocolMessageType == ProtocolMessageType.HANDSHAKE || rcvRecordProtocolMessageType == ProtocolMessageType.CHANGE_CIPHER_SPEC)
		&& (System.currentTimeMillis() <= endTimeMillies)) {
	    try {
		rcvRecord = receiveNextValidRecord();
	    } catch (SocketTimeoutException ste) {
		continue;
	    }
	    rcvRecordProtocolMessageType = ProtocolMessageType.getContentType(rcvRecord.getContentType().getValue());
	    switch (rcvRecordProtocolMessageType) {
		case HANDSHAKE:
		    handshakeFragmentHandler.processHandshakeRecord(rcvRecord);
		    break;
		case CHANGE_CIPHER_SPEC:
		    processChangeCipherSpecRecord(rcvRecord);
		    break;
		default:
		    return rcvRecord.getProtocolMessageBytes().getValue();
	    }
	}
	return null;
    }

    protected byte[] getChangeCipherSpecMessage() throws Exception {
	Record rcvRecord;
	ProtocolMessageType rcvRecordProtocolMessageType;
	long endTimeMillies = System.currentTimeMillis() + maxWaitForExpectedRecord;

	while (!changeCipherSpecReceived() && (System.currentTimeMillis() <= endTimeMillies)) {
	    try {
		rcvRecord = receiveNextValidRecord();
	    } catch (SocketTimeoutException ste) {
		continue;
	    }
	    rcvRecordProtocolMessageType = ProtocolMessageType.getContentType(rcvRecord.getContentType().getValue());
	    switch (rcvRecordProtocolMessageType) {
		case CHANGE_CIPHER_SPEC:
		    processChangeCipherSpecRecord(rcvRecord);
		    break;
		case HANDSHAKE:
		    handshakeFragmentHandler.processHandshakeRecord(rcvRecord);
		    break;
		case ALERT:
		    return rcvRecord.getProtocolMessageBytes().getValue();
		default:
		    break;
	    }
	}
	if (changeCipherSpecReceived()) {
	    return getReceivedChangeCipherSepc();
	}
	return null;
    }

    private boolean changeCipherSpecReceived() {
	return changeCipherSpecBytes != null;
    }

    private byte[] getReceivedChangeCipherSepc() {
	byte[] output = changeCipherSpecBytes;
	changeCipherSpecBytes = null;
	return output;
    }

    private void processChangeCipherSpecRecord(Record ccsRecord) {
	if (changeCipherSpecBytes == null) {
	    changeCipherSpecBytes = ccsRecord.getProtocolMessageBytes().getValue();
	}
    }

    private Record receiveNextValidRecord() throws Exception {
	Record nextRecord = receiveNextRecord();
	while (!checkRecordValidity(nextRecord)) {
	    nextRecord = receiveNextRecord();
	}
	return nextRecord;
    }

    private Record receiveNextRecord() throws Exception {
	if (recordBuffer.isEmpty()) {
	    processNextPacket();
	}
	Record out = (Record) recordBuffer.get(0);
	recordBuffer.remove(0);
	return out;
    }

    private boolean checkRecordValidity(Record record) {
	if (record.getEpoch().getValue() != epochCounter) {
	    return false;
	}
	return true;
    }

    private void processNextPacket() throws Exception {
	recordBuffer = recordHandler.parseRecords(receiveNextPacket());
    }

    private byte[] receiveNextPacket() throws Exception {
	return transportHandler.fetchData();
    }

    public void setMaxPacketSize(int maxPacketSize) {
	if (this.maxPacketSize > 16397) {
	    this.maxPacketSize = 16397;
	} else {
	    this.maxPacketSize = maxPacketSize;
	}
    }
}
