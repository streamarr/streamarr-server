# Test TLS fixtures

These certificates are test-only fixtures. They use P-256 keys, a private `Streamarr-Test-CA`,
and a ten-year lifetime so normal builds do not depend on an external PKI. The mapped worker URI
must remain `spiffe://streamarr.test/streamarr/worker/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`; the
unmapped certificate deliberately uses the wrong trust domain.

Regenerate them with OpenSSL 3 from this directory. Work in a temporary directory so the CA key
and certificate-signing requests are never copied into the repository:

```bash
fixture_dir=$(pwd)
work_dir=$(mktemp -d)
cd "$work_dir"

openssl ecparam -name prime256v1 -genkey -noout -out ca-key.pem
openssl req -x509 -new -sha256 -key ca-key.pem -days 3650 \
  -subj /CN=Streamarr-Test-CA -out ca-cert.pem \
  -addext basicConstraints=critical,CA:TRUE \
  -addext keyUsage=critical,keyCertSign,cRLSign

openssl ecparam -name prime256v1 -genkey -noout -out server-key.fixture
openssl req -new -sha256 -key server-key.fixture -subj /CN=localhost -out server.csr
printf '%s\n' 'basicConstraints=critical,CA:FALSE' \
  'keyUsage=critical,digitalSignature,keyEncipherment' \
  'extendedKeyUsage=serverAuth' \
  'subjectAltName=DNS:localhost,IP:127.0.0.1' > server.ext
openssl x509 -req -sha256 -in server.csr -CA ca-cert.pem -CAkey ca-key.pem \
  -CAcreateserial -days 3650 -extfile server.ext -out server-cert.pem

worker_id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa
for name in worker unmapped-worker; do
  openssl ecparam -name prime256v1 -genkey -noout -out "$name-key.fixture"
  openssl req -new -sha256 -key "$name-key.fixture" -subj "/CN=$name" -out "$name.csr"
done
printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature' \
  'extendedKeyUsage=clientAuth' \
  "subjectAltName=URI:spiffe://streamarr.test/streamarr/worker/$worker_id" > worker.ext
printf '%s\n' 'basicConstraints=critical,CA:FALSE' 'keyUsage=critical,digitalSignature' \
  'extendedKeyUsage=clientAuth' \
  "subjectAltName=URI:spiffe://wrong.test/streamarr/worker/$worker_id" > unmapped-worker.ext
for name in worker unmapped-worker; do
  openssl x509 -req -sha256 -in "$name.csr" -CA ca-cert.pem -CAkey ca-key.pem \
    -CAcreateserial -days 3650 -extfile "$name.ext" -out "$name-cert.pem"
done

cp ca-cert.pem server-cert.pem server-key.fixture worker-cert.pem worker-key.fixture \
  unmapped-worker-cert.pem unmapped-worker-key.fixture "$fixture_dir/"
```

After regeneration, run `RemoteTranscodeConfigurationTest`, `WorkerIdentityServerInterceptorTest`,
`WorkerSessionServerIT`, and the worker integration tests that reference `/tls/`.
