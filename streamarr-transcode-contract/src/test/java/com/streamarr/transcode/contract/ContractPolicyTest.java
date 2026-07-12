package com.streamarr.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodOptions.IdempotencyLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ContractPolicyTest {

  private static final String PACKAGE = "streamarr.transcode.v1";
  private static final String JAVA_PACKAGE = "com.streamarr.transcode.v1";
  private static final Set<String> ALLOWED_BYTES_FIELDS =
      Set.of(
          "certificate_signing_request_der",
          "continuation",
          "data",
          "issuer_certificate_sha256",
          "issuer_certificates_der",
          "leaf_certificate_der",
          "next_continuation",
          "nonce",
          "serial_number",
          "serialized_update",
          "signature",
          "signer_certificate_sha256",
          "revocation_signer_certificates_der",
          "trust_anchor_certificates_der");

  @Test
  @DisplayName("Should keep generated Java types isolated in the versioned transport package")
  void shouldKeepGeneratedJavaTypesIsolatedInVersionedTransportPackage() throws Exception {
    var files = contractFiles();

    assertThat(files)
        .allSatisfy(
            file -> {
              assertThat(file.getOptions().getJavaPackage()).isEqualTo(JAVA_PACKAGE);
              assertThat(file.getOptions().getJavaMultipleFiles()).isTrue();
              assertThat(file.getOptions().getJavaOuterClassname()).endsWith("Proto");
            });
  }

  @Test
  @DisplayName("Should expose only typed bounded transport fields when describing the contract")
  void shouldExposeOnlyTypedBoundedTransportFieldsWhenDescribingContract() throws Exception {
    var messages = messagesByName(contractFiles());
    var fields = messages.values().stream().flatMap(ContractPolicyTest::fields);

    assertThat(messages.values()).noneMatch(message -> message.getOptions().getMapEntry());
    assertThat(fields)
        .allSatisfy(
            field -> {
              assertThat(field.getTypeName())
                  .isNotIn(
                      ".google.protobuf.Any", ".google.protobuf.Struct", ".google.protobuf.Value");
              assertThat(field.getName())
                  .doesNotContainIgnoringCase("argv")
                  .doesNotContainIgnoringCase("password")
                  .doesNotContainIgnoringCase("pid")
                  .doesNotContainIgnoringCase("secret")
                  .doesNotContainIgnoringCase("token");
              if (field.getType() == FieldDescriptorProto.Type.TYPE_BYTES) {
                assertThat(field.getName()).isIn(ALLOWED_BYTES_FIELDS);
              }
            });

    assertThat(messages.get("ReadSegmentResponse").getFieldList())
        .extracting(FieldDescriptorProto::getName)
        .containsExactly("metadata", "data");
    assertThat(messages.get("SegmentMetadata").getFieldList())
        .extracting(FieldDescriptorProto::getName)
        .containsExactly("content_length_bytes", "content_type");
    assertThat(
            messages.get("SegmentMetadata").getFieldList().stream()
                .collect(
                    Collectors.toMap(FieldDescriptorProto::getName, FieldDescriptorProto::getType)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "content_length_bytes", FieldDescriptorProto.Type.TYPE_UINT64,
                "content_type", FieldDescriptorProto.Type.TYPE_ENUM));
    assertThat(messages.get("EnrollWorkerRequest").getFieldList())
        .extracting(FieldDescriptorProto::getName)
        .containsExactly("request_id", "worker_id", "certificate_signing_request_der");
    assertThat(messages.get("PublicTrustBundle").getFieldList())
        .extracting(FieldDescriptorProto::getName)
        .containsExactly(
            "version",
            "trust_anchor_certificates_der",
            "issuer_certificates_der",
            "revocation_signer_certificates_der");
    assertThat(messages.get("CertificateBundle").getFieldList())
        .extracting(FieldDescriptorProto::getName)
        .containsExactly("worker_id", "leaf_certificate_der", "trust_bundle");
    assertThat(
            messages.get("TranscodeExecutionParameters").getFieldList().stream()
                .collect(
                    Collectors.toMap(FieldDescriptorProto::getName, FieldDescriptorProto::getType)))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "seek_position_seconds", FieldDescriptorProto.Type.TYPE_UINT32,
                "segment_duration_seconds", FieldDescriptorProto.Type.TYPE_UINT32,
                "framerate", FieldDescriptorProto.Type.TYPE_DOUBLE,
                "start_number", FieldDescriptorProto.Type.TYPE_UINT32,
                "startup_timeout", FieldDescriptorProto.Type.TYPE_MESSAGE));
  }

  @Test
  @DisplayName("Should reserve zero as an explicitly unspecified value for every enum")
  void shouldReserveZeroAsExplicitlyUnspecifiedValueForEveryEnum() throws Exception {
    var enums =
        contractFiles().stream()
            .flatMap(file -> Stream.concat(file.getEnumTypeList().stream(), nestedEnums(file)))
            .toList();

    assertThat(enums).isNotEmpty();
    assertThat(enums)
        .allSatisfy(
            descriptor -> {
              var zero =
                  descriptor.getValueList().stream()
                      .filter(value -> value.getNumber() == 0)
                      .toList();
              assertThat(zero).singleElement();
              assertThat(zero.getFirst().getName())
                  .isEqualTo(toUpperSnakeCase(descriptor.getName()) + "_UNSPECIFIED");
            });
  }

  @Test
  @DisplayName("Should declare retry semantics for every remote operation")
  void shouldDeclareRetrySemanticsForEveryRemoteOperation() throws Exception {
    var methods =
        contractFiles().stream()
            .flatMap(file -> file.getServiceList().stream())
            .flatMap(service -> service.getMethodList().stream())
            .collect(
                Collectors.toMap(
                    method -> method.getName(),
                    method -> method.getOptions().getIdempotencyLevel()));

    assertThat(methods)
        .containsExactlyInAnyOrderEntriesOf(
            Map.ofEntries(
                Map.entry("ProveEndpoint", IdempotencyLevel.NO_SIDE_EFFECTS),
                Map.entry("StartJob", IdempotencyLevel.IDEMPOTENT),
                Map.entry("StopJob", IdempotencyLevel.IDEMPOTENT),
                Map.entry("InspectJob", IdempotencyLevel.NO_SIDE_EFFECTS),
                Map.entry("ListJobs", IdempotencyLevel.NO_SIDE_EFFECTS),
                Map.entry("ReadSegment", IdempotencyLevel.NO_SIDE_EFFECTS),
                Map.entry("RegisterWorker", IdempotencyLevel.IDEMPOTENT),
                Map.entry("Heartbeat", IdempotencyLevel.IDEMPOTENCY_UNKNOWN),
                Map.entry("EnrollWorker", IdempotencyLevel.IDEMPOTENT),
                Map.entry("RotateWorkerCertificate", IdempotencyLevel.IDEMPOTENT)));
  }

  private static java.util.List<FileDescriptorProto> contractFiles() throws Exception {
    var descriptorPath = Path.of(System.getProperty("contract.descriptor.file"));
    assertThat(descriptorPath).isRegularFile();
    return FileDescriptorSet.parseFrom(Files.readAllBytes(descriptorPath)).getFileList().stream()
        .filter(file -> PACKAGE.equals(file.getPackage()))
        .toList();
  }

  private static Map<String, DescriptorProto> messagesByName(
      java.util.List<FileDescriptorProto> files) {
    return files.stream()
        .flatMap(file -> file.getMessageTypeList().stream())
        .collect(Collectors.toMap(DescriptorProto::getName, Function.identity()));
  }

  private static Stream<FieldDescriptorProto> fields(DescriptorProto message) {
    return Stream.concat(
        message.getFieldList().stream(),
        message.getNestedTypeList().stream().flatMap(ContractPolicyTest::fields));
  }

  private static Stream<EnumDescriptorProto> nestedEnums(FileDescriptorProto file) {
    return file.getMessageTypeList().stream().flatMap(ContractPolicyTest::nestedEnums);
  }

  private static Stream<EnumDescriptorProto> nestedEnums(DescriptorProto message) {
    return Stream.concat(
        message.getEnumTypeList().stream(),
        message.getNestedTypeList().stream().flatMap(ContractPolicyTest::nestedEnums));
  }

  private static String toUpperSnakeCase(String value) {
    return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
  }
}
