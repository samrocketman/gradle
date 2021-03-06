/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.fingerprint.impl;

import com.google.common.base.Objects;
import org.gradle.api.internal.cache.StringInterner;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.FileFingerprint;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.serialize.AbstractSerializer;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.HashCodeSerializer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class FingerprintMapSerializer extends AbstractSerializer<Map<String, FileFingerprint>> {
    private static final byte DEFAULT_NORMALIZATION = 1;
    private static final byte IGNORED_PATH_NORMALIZATION = 2;

    private static final byte DIR_FINGERPRINT = 1;
    private static final byte MISSING_FILE_FINGERPRINT = 2;
    private static final byte REGULAR_FILE_FINGERPRINT = 3;

    private final HashCodeSerializer hashCodeSerializer = new HashCodeSerializer();
    private final StringInterner stringInterner;

    public FingerprintMapSerializer(StringInterner stringInterner) {
        this.stringInterner = stringInterner;
    }

    @Override
    public Map<String, FileFingerprint> read(Decoder decoder) throws IOException {
        int fingerprintCount = decoder.readSmallInt();
        Map<String, FileFingerprint> fingerprints = new LinkedHashMap<String, FileFingerprint>(fingerprintCount);
        for (int i = 0; i < fingerprintCount; i++) {
            String absolutePath = stringInterner.intern(decoder.readString());
            FileFingerprint fingerprint = readFingerprint(decoder);
            fingerprints.put(absolutePath, fingerprint);
        }
        return fingerprints;
    }

    private FileFingerprint readFingerprint(Decoder decoder) throws IOException {
        FileType fileType = readFileType(decoder);
        HashCode contentHash = readContentHash(fileType, decoder);

        byte fingerprintKind = decoder.readByte();
        switch (fingerprintKind) {
            case DEFAULT_NORMALIZATION:
                String normalizedPath = decoder.readString();
                return new DefaultFileFingerprint(stringInterner.intern(normalizedPath), fileType, contentHash);
            case IGNORED_PATH_NORMALIZATION:
                return IgnoredPathFingerprint.create(fileType, contentHash);
            default:
                throw new RuntimeException("Unable to read serialized file fingerprint. Unrecognized value found in the data stream.");
        }
    }

    private HashCode readContentHash(FileType fileType, Decoder decoder) throws IOException {
        switch (fileType) {
            case Directory:
                return FileFingerprint.DIR_SIGNATURE;
            case Missing:
                return FileFingerprint.MISSING_FILE_SIGNATURE;
            case RegularFile:
                return hashCodeSerializer.read(decoder);
            default:
                throw new RuntimeException("Unable to read serialized file fingerprint. Unrecognized value found in the data stream.");
        }
    }

    private FileType readFileType(Decoder decoder) throws IOException {
        byte fileKind = decoder.readByte();
        switch (fileKind) {
            case DIR_FINGERPRINT:
                return FileType.Directory;
            case MISSING_FILE_FINGERPRINT:
                return FileType.Missing;
            case REGULAR_FILE_FINGERPRINT:
                return FileType.RegularFile;
            default:
                throw new RuntimeException("Unable to read serialized file fingerprint. Unrecognized value found in the data stream.");
        }
    }

    @Override
    public void write(Encoder encoder, Map<String, FileFingerprint> value) throws Exception {
        encoder.writeSmallInt(value.size());
        for (String key : value.keySet()) {
            encoder.writeString(key);
            FileFingerprint fingerprint = value.get(key);
            writeFingerprint(encoder, fingerprint);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        FingerprintMapSerializer rhs = (FingerprintMapSerializer) obj;
        return Objects.equal(hashCodeSerializer, rhs.hashCodeSerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), hashCodeSerializer);
    }

    private void writeFingerprint(Encoder encoder, FileFingerprint value) throws IOException {
        switch (value.getType()) {
            case Directory:
                encoder.writeByte(DIR_FINGERPRINT);
                break;
            case Missing:
                encoder.writeByte(MISSING_FILE_FINGERPRINT);
                break;
            case RegularFile:
                encoder.writeByte(REGULAR_FILE_FINGERPRINT);
                hashCodeSerializer.write(encoder, value.getNormalizedContentHash());
                break;
            default:
                throw new AssertionError();
        }

        if (value instanceof DefaultFileFingerprint) {
            encoder.writeByte(DEFAULT_NORMALIZATION);
            encoder.writeString(value.getNormalizedPath());
        } else if (value instanceof IgnoredPathFingerprint) {
            encoder.writeByte(IGNORED_PATH_NORMALIZATION);
        } else {
            throw new AssertionError();
        }
    }
}
