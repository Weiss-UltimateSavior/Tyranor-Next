package com.core.ethornell;

/** Only known JNI status syntax and explicitly redacted classes may enter production logs. */
final class EthornellFailureDetail {
    private EthornellFailureDetail() {}
    static String safe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.length() > 2100) return "status=host_failure";
        for (int i = 0; i < message.length(); i++)
            if (message.charAt(i) < 0x20 || message.charAt(i) > 0x7e) return "status=host_failure";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "Ethornell status (-[0-9]{1,3})(?:: (.*))?").matcher(message);
        if (!matcher.matches()) return "status=host_failure";
        String result = "status=" + matcher.group(1), detail = matcher.group(2);
        if (detail == null) return result + " detail_state=absent";
        String category = detail;
        int vmStart = detail.indexOf(" stop=");
        if (vmStart >= 0) {
            if (!safeVm(detail.substring(vmStart))) return result + " detail_state=rejected_vm_fields";
            category = detail.substring(0, vmStart);
        }
        if (category.matches("filesystem policy stage=[0-9]{1,10} errno=-?[0-9]{1,11}") || safeGpu(category)
                || category.equals("unsupported host/game capability")
                || category.equals("filesystem policy denied operation")
                || category.equals("CJK font unavailable or invalid")
                || category.equals("GPU surface/device initialization or rendering failed")
                || category.equals("audio initialization or suspension failed")
                || category.equals("runtime resource/VM operation failed")
                || category.equals("internal panic; session poisoned")
                || category.equals("internal panic; session cannot continue")) return result + " detail=" + detail;
        return result + " detail_state=rejected_category";
    }
    private static boolean safeVm(String suffix) {
        java.util.regex.Matcher fields = java.util.regex.Pattern.compile(
                " stop=([0-9]{1,2}) pc=([0-9]{1,20}) offset=([0-9]{1,20}) group=([0-9]{1,10}) id=([0-9]{1,10})(?: cause=[0-8](?: origin=[0-5])?)?").matcher(suffix);
        if (!fields.matches()) return false;
        try {
            if (Integer.parseInt(fields.group(1)) > 15) return false;
            Long.parseUnsignedLong(fields.group(2)); Long.parseUnsignedLong(fields.group(3));
            long group = Long.parseLong(fields.group(4)), id = Long.parseLong(fields.group(5));
            return (group <= 255 || group == 4294967295L) && (id <= 65535 || id == 4294967295L);
        } catch (NumberFormatException invalid) { return false; }
    }
    private static boolean safeGpu(String detail) {
        if (detail.length() > 768) return false;
        String reason = "(?:adapter_unavailable|unsupported_limits|request_device_failed|surface_formats_empty|surface_present_modes_empty|surface_alpha_modes_empty|surface_render_attachment_unavailable|surface_creation_failed|invalid_dimensions|uncaptured_validation_error|device_lost)";
        String limit = "(?:max_texture_dimension_[123]d|max_texture_array_layers|max_bind_groups|max_bindings_per_bind_group|max_dynamic_uniform_buffers_per_pipeline_layout|max_dynamic_storage_buffers_per_pipeline_layout|max_sampled_textures_per_shader_stage|max_samplers_per_shader_stage|max_storage_buffers_per_shader_stage|max_storage_textures_per_shader_stage|max_uniform_buffers_per_shader_stage|max_uniform_buffer_binding_size|max_storage_buffer_binding_size|max_vertex_buffers|max_buffer_size|max_vertex_attributes|max_vertex_buffer_array_stride|min_uniform_buffer_offset_alignment|min_storage_buffer_offset_alignment|max_inter_stage_shader_components|max_compute_workgroup_storage_size|max_compute_invocations_per_workgroup|max_compute_workgroup_size_[xyz]|max_compute_workgroups_per_dimension)";
        return detail.matches("GPU stage=[1-7] reason=" + reason + "(?: " + limit + "=[0-9]{1,20}/[0-9]{1,20})*");
    }
}
