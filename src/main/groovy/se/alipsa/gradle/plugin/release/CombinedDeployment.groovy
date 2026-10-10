package se.alipsa.gradle.plugin.release

/** One-attempt upload journaling and status-only recovery. */
class CombinedDeployment {
    /** Uploads once, persisting intent before HTTP and ID immediately after it. */
    static Map submit(File record, File bundle, Map manifest, PublicationStatus availability, CentralPortalClient client) {
        if (record.exists()) throw new IllegalStateException('Deployment record exists; resume or reconcile it before a new upload')
        manifest.selected.each { Map component ->
            if (availability.published(new ReleaseCoordinate(component.groupId, component.artifactId, component.version), true)) {
                throw new IllegalStateException("Component was published since planning: ${component.artifactId}")
            }
        }
        Map state = [state: 'SUBMITTING', bundleHash: ReleaseChecksums.checksum(bundle, 'SHA-256'),
                     manifest: manifest, created: java.time.Instant.now().toString()]
        // Reserve the active journal atomically: two local invocations must not both submit.
        record.parentFile.mkdirs()
        try {
            java.nio.file.Files.writeString(record.toPath(), groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(state)), java.nio.file.StandardOpenOption.CREATE_NEW)
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            throw new IllegalStateException('Deployment record was created concurrently; resume or reconcile it before a new upload')
        }
        // No retry: even an exception may mean the server accepted the upload.
        String id
        try { id = client.upload(bundle) }
        catch (Exception failure) {
            Throwable cause = failure
            while (cause.cause && !(cause instanceof WsException)) cause = cause.cause
            boolean rejected = cause instanceof WsException && cause.statusCode in [401, 403, 422]
            state.state = rejected ? 'UPLOAD_REJECTED' : 'UPLOAD_ERROR'
            state.error = failure.message
            if (cause instanceof WsException) {
                state.httpStatus = cause.statusCode
                state.rawResponse = cause.body
            }
            ReleaseManifest.write(record, state)
            if (rejected) {
                // Retain the rejected attempt, freeing the active journal only after a definite rejection.
                java.nio.file.Files.move(record.toPath(), new File(record.path + '.rejected-' + UUID.randomUUID() + '.json').toPath())
            }
            throw failure
        }
        if (!id || !(id ==~ /[A-Za-z0-9-]+/)) {
            state.rawResponse = id
            state.error = 'Upload response did not contain a valid deployment ID'
            ReleaseManifest.write(record, state)
            throw new IllegalStateException('Upload outcome unknown; reconcile SUBMITTING record in Portal')
        }
        state.deploymentId = id
        state.state = 'UPLOADED'
        ReleaseManifest.write(record, state)
        state
    }

    /** Polls recorded ID without source guards, rebuilding, or another submission. */
    static void resume(File record, String id, CentralPortalClient client, Closure<Boolean> available, int attempts = 60, long interval = 10000) {
        Map state = ReleaseManifest.read(record)
        if (!id || state.deploymentId != id) throw new IllegalStateException('Deployment ID does not match the saved record')
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                Map status = client.getDeploymentStatus(id)
                if (!(status?.deploymentState in ['PENDING', 'VALIDATING', 'VALIDATED', 'PUBLISHING', 'PUBLISHED', 'FAILED'])) {
                    throw new IOException('Invalid deployment status response: missing or unknown deploymentState')
                }
                state.state = status.deploymentState
                ReleaseManifest.write(record, state)
                if (state.state == 'FAILED') {
                    String coordinates = state.manifest.selected.collect { "${it.groupId}:${it.artifactId}:${it.version}" }.join(', ')
                    throw new IllegalStateException("Deployment ${id} failed for ${coordinates}; inspect Portal for component validation errors")
                }
                if (state.state == 'PUBLISHED' && available(state.manifest as Map)) {
                    state.state = 'AVAILABLE'
                    ReleaseManifest.write(record, state)
                    return
                }
            } catch (IOException failure) {
                if (failure instanceof WsException && failure.statusCode in [401, 403]) throw failure
                state.lastPollingError = failure.message
                ReleaseManifest.write(record, state)
            }
            if (attempt + 1 < attempts) Thread.sleep(interval)
        }
        throw new IllegalStateException("Deployment ${id} is pending publication/propagation; resume without uploading")
    }
}
