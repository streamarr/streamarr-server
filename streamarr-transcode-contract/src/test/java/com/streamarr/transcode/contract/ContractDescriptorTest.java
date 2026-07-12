package com.streamarr.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class ContractDescriptorTest {

  private static final String PACKAGE = "streamarr.transcode.v1";

  @Test
  @DisplayName("Should publish the complete v1 service surface when generating the descriptor")
  void shouldPublishCompleteV1ServiceSurfaceWhenGeneratingDescriptor() throws Exception {
    var descriptor = readDescriptor();
    var contractFiles =
        descriptor.getFileList().stream()
            .filter(file -> PACKAGE.equals(file.getPackage()))
            .toList();

    assertThat(contractFiles)
        .extracting(FileDescriptorProto::getName)
        .containsExactlyInAnyOrder(
            "streamarr/transcode/v1/common.proto",
            "streamarr/transcode/v1/transcode.proto",
            "streamarr/transcode/v1/worker_control.proto",
            "streamarr/transcode/v1/worker_registry.proto",
            "streamarr/transcode/v1/worker_enrollment.proto");

    var services =
        contractFiles.stream()
            .flatMap(file -> file.getServiceList().stream())
            .collect(Collectors.toMap(service -> service.getName(), Function.identity()));

    assertThat(services)
        .containsOnlyKeys(
            "WorkerControlService", "WorkerRegistryService", "WorkerEnrollmentService");
    assertMethods(
        services.get("WorkerControlService").getMethodList(),
        Map.of(
            "ProveEndpoint", method("ProveEndpointRequest", "ProveEndpointResponse", false),
            "StartJob", method("StartJobRequest", "StartJobResponse", false),
            "StopJob", method("StopJobRequest", "StopJobResponse", false),
            "InspectJob", method("InspectJobRequest", "InspectJobResponse", false),
            "ListJobs", method("ListJobsRequest", "ListJobsResponse", false),
            "ReadSegment", method("ReadSegmentRequest", "ReadSegmentResponse", true)));
    assertMethods(
        services.get("WorkerRegistryService").getMethodList(),
        Map.of(
            "RegisterWorker", method("RegisterWorkerRequest", "RegisterWorkerResponse", false),
            "Heartbeat", method("HeartbeatRequest", "HeartbeatResponse", false)));
    assertMethods(
        services.get("WorkerEnrollmentService").getMethodList(),
        Map.of(
            "EnrollWorker", method("EnrollWorkerRequest", "EnrollWorkerResponse", false),
            "RotateWorkerCertificate",
                method(
                    "RotateWorkerCertificateRequest", "RotateWorkerCertificateResponse", false)));
  }

  private static void assertMethods(
      java.util.List<MethodDescriptorProto> methods, Map<String, MethodContract> expected) {
    var actual =
        methods.stream()
            .collect(
                Collectors.toMap(
                    MethodDescriptorProto::getName,
                    method ->
                        new MethodContract(
                            simpleName(method.getInputType()),
                            simpleName(method.getOutputType()),
                            method.getServerStreaming())));

    assertThat(actual).isEqualTo(expected);
    assertThat(methods).allSatisfy(method -> assertThat(method.getClientStreaming()).isFalse());
  }

  private static MethodContract method(String input, String output, boolean serverStreaming) {
    return new MethodContract(input, output, serverStreaming);
  }

  private static String simpleName(String qualifiedName) {
    return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
  }

  private static FileDescriptorSet readDescriptor() throws Exception {
    var descriptorPath = Path.of(System.getProperty("contract.descriptor.file"));
    assertThat(descriptorPath).isRegularFile();
    return FileDescriptorSet.parseFrom(Files.readAllBytes(descriptorPath));
  }

  private record MethodContract(String input, String output, boolean serverStreaming) {}
}
