package com.streamarr.transcode.tls;

import java.nio.file.Path;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PemTlsIdentity(
    @NonNull Path certificate, @NonNull Path privateKey, @NonNull Path trustBundle) {}
