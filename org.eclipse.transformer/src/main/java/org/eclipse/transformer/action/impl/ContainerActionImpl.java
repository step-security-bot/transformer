/********************************************************************************
 * Copyright (c) Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: (EPL-2.0 OR Apache-2.0)
 ********************************************************************************/

package org.eclipse.transformer.action.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.eclipse.transformer.TransformException;
import org.eclipse.transformer.action.Action;
import org.eclipse.transformer.action.ActionType;
import org.eclipse.transformer.action.ByteData;
import org.eclipse.transformer.action.ContainerAction;
import org.eclipse.transformer.action.ContainerChanges;
import org.eclipse.transformer.action.InputBuffer;
import org.eclipse.transformer.action.SelectionRule;
import org.eclipse.transformer.action.SignatureRule;
import org.eclipse.transformer.util.FileUtils;
import org.slf4j.Logger;

import aQute.lib.io.IO;

public abstract class ContainerActionImpl extends ActionImpl<ContainerChangesImpl> implements ContainerAction {

	public <A extends Action> A addUsing(ActionInit<A> init) {
		A action = createUsing(init);
		addAction(action);
		return action;
	}

	public ContainerActionImpl(Logger logger, InputBuffer buffer, SelectionRule selectionRule,
		SignatureRule signatureRule) {

		super(logger, buffer, selectionRule, signatureRule);

		this.compositeAction = createUsing(CompositeActionImpl::new);
	}

	//

	private final CompositeActionImpl compositeAction;

	@Override
	public CompositeActionImpl getAction() {
		return compositeAction;
	}

	public void addAction(Action action) {
		getAction().addAction(action);
	}

	@Override
	public List<Action> getActions() {
		return getAction().getActions();
	}

	@Override
	public String getAcceptExtension() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Action acceptAction(String resourceName) {
		return acceptAction(resourceName, null);
	}

	@Override
	public Action acceptAction(String resourceName, File resourceFile) {
		return getAction().acceptAction(resourceName, resourceFile);
	}

	//

	@Override
	public abstract String getName();

	@Override
	public abstract ActionType getActionType();

	//

	@Override
	protected ContainerChangesImpl newChanges() {
		return new ContainerChangesImpl();
	}

	//

	protected void recordUnaccepted(String resourceName) {
		getLogger().debug("Resource [ {} ]: Not accepted", resourceName);

		getActiveChanges().record();
	}

	protected void recordUnselected(Action action, String resourceName) {
		getLogger().debug("Resource [ {} ] Action [ {} ]: Accepted but not selected", resourceName, action.getName());

		getActiveChanges().record(action, !ContainerChanges.HAS_CHANGES);
	}

	protected void recordTransform(Action action, String resourceName) {
		getLogger().debug("Resource [ {} ] Action [ {} ]: Changes [ {} ]", resourceName, action.getName(),
			action.hadChanges());

		getActiveChanges().record(action);
	}

	// Byte base container conversion is not supported.

	@Override
	public boolean useStreams() {
		return true;
	}

	@Override
	public ByteData apply(ByteData inputData) throws TransformException {
		throw new UnsupportedOperationException();
	}

	// Containers default to process input streams as zip archives.

	@Override
	public void apply(String inputPath, InputStream inputStream, long inputCount, OutputStream outputStream)
		throws TransformException {

		startRecording(inputPath);

		try {
			setResourceNames(inputPath, inputPath);

			// Use Zip streams instead of Jar streams.
			//
			// Jar streams automatically read and consume the manifest, which we
			// don't want.
			try {
				ZipInputStream zipInputStream = new ZipInputStream(inputStream);
				ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);
				try {
					apply(inputPath, zipInputStream, zipOutputStream);
				} finally {
					zipOutputStream.finish();
				}
			} catch (IOException e) {
				throw new TransformException("Failed to complete output [ " + inputPath + " ]", e);
			}

		} finally {
			stopRecording(inputPath);
		}
	}

	protected void apply(String inputPath, ZipInputStream zipInputStream, ZipOutputStream zipOutputStream)
		throws TransformException {

		String prevName = null;
		String inputName = null;

		try {
			byte[] copyBuffer = new byte[FileUtils.BUFFER_ADJUSTMENT];

			for (ZipEntry inputEntry; (inputEntry = zipInputStream.getNextEntry()) != null;) {
				inputName = inputEntry.getName();
				long inputLength = inputEntry.getSize();

				getLogger().debug("[ {}.{} ] [ {} ] Size [ {} ]", getClass().getSimpleName(), "apply", inputName,
					inputLength);

				boolean selected = select(inputName);
				Action acceptedAction = acceptAction(inputName);

				if (!selected || (acceptedAction == null)) {
					if (acceptedAction == null) {
						recordUnaccepted(inputName);
					} else {
						recordUnselected(acceptedAction, inputName);
					}

					ZipEntry outputEntry = new ZipEntry(inputEntry);
					outputEntry.setCompressedSize(-1L);
					zipOutputStream.putNextEntry(outputEntry);
					FileUtils.transfer(zipInputStream, zipOutputStream, copyBuffer);
					zipOutputStream.closeEntry();
				} else {
					// Archive type actions are processed using streams,
					// while non-archive type actions do a full read of the
					// entry data and process the resulting byte array.
					//
					// Ideally, a single pattern would be used for both cases,
					// but that is not possible:
					//
					// A full read of a nested archive is not possible because
					// the nested archive can be very large.
					//
					// A read of non-archive data must be performed, since
					// non-archive data may change the name associated with the
					// data, and that can only be determined after reading the
					// data.
					if (acceptedAction.useStreams()) {
						ZipEntry outputEntry = new ZipEntry(inputName);
						outputEntry.setExtra(inputEntry.getExtra());
						outputEntry.setComment(inputEntry.getComment());
						zipOutputStream.putNextEntry(outputEntry);
						acceptedAction.apply(inputName, zipInputStream, inputLength, zipOutputStream);
						recordTransform(acceptedAction, inputName);
						zipOutputStream.closeEntry();
					} else {
						int intInputLength;
						if (inputLength == -1L) {
							intInputLength = -1;
						} else {
							intInputLength = FileUtils.verifyArray(0, inputLength);
						}
						ByteData outputData = acceptedAction.apply(inputName, zipInputStream, intInputLength);
						recordTransform(acceptedAction, inputName);

						ZipEntry outputEntry = new ZipEntry(acceptedAction.getLastActiveChanges()
							.getOutputResourceName());
						outputEntry.setMethod(inputEntry.getMethod());
						outputEntry.setExtra(inputEntry.getExtra());
						outputEntry.setComment(inputEntry.getComment());
						ByteBuffer buffer = outputData.buffer();
						if (outputEntry.getMethod() == ZipOutputStream.STORED) {
							outputEntry.setSize(buffer.remaining());
							outputEntry.setCompressedSize(buffer.remaining());
							CRC32 crc = new CRC32();
							buffer.mark();
							crc.update(buffer);
							buffer.reset();
							outputEntry.setCrc(crc.getValue());
						}
						zipOutputStream.putNextEntry(outputEntry);
						IO.copy(buffer, zipOutputStream);
						zipOutputStream.closeEntry();
					}
				}

				prevName = inputName;
				inputName = null;
			}
		} catch (IOException e) {
			String message;
			if (inputName != null) { // Actively processing an entry.
				message = "Failure while processing [ " + inputName + " ] from [ " + inputPath + " ]";
			} else if (prevName != null) { // Moving to a new entry but not the
											// first entry.
				message = "Failure after processing [ " + prevName + " ] from [ " + inputPath + " ]";
			} else { // Moving to the first entry.
				message = "Failed to process first entry of [ " + inputPath + " ]";
			}
			throw new TransformException(message, e);
		}
	}
}
