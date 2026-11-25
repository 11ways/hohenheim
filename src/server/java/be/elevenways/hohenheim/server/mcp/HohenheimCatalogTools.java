package be.elevenways.hohenheim.server.mcp;

import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.cms.CatalogRecordWrites;
import be.elevenways.hohenheim.server.cms.PreviewParts;
import be.elevenways.hohenheim.server.instance.TemplateDocuments;
import be.elevenways.zenit.ai.server.mcp.host.McpCodeTool;
import be.elevenways.zenit.ai.server.mcp.host.McpHostTool;
import be.elevenways.zenit.ai.server.mcp.host.McpResponse;
import be.elevenways.zenit.ai.server.mcp.host.McpToolContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's hand-declared tools over an app's parts made beside it (its devices, previews and
 * template scripts), runtime images and templates, each over the operations the panel runs; {@link HohenheimTools}
 * offers them beside its own.
 *
 * AIDEV-NOTE: declarations only, as in HohenheimTools: a gate, a check or a writer of a tool here is its operation's.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class HohenheimCatalogTools {

    private static final String SCRIPT = "script";
    private static final String APPROVED = "approved";
    private static final String FROM_APP = "from_app";

    private HohenheimCatalogTools() {
    }

    /** @return every tool of this class, in the order an assistant is offered them */
    static @NonNull List<McpHostTool> declared() {
        // One effect per tool: a reinstall with the template's clear policy removes the app's volumes.
        McpHostTool scriptRun = McpCodeTool.builder("app_script_run", HohenheimTools.words("mcp_tool_app_script_run"),
                HohenheimTools.destroys())
            .code(HohenheimTools.words("mcp_code_app_script_run"))
            .choice(SCRIPT, HohenheimTools.words("mcp_app_script"), List.of("install", "reinstall", "update"), null)
            .route(InstanceOperations.INSTALL, null, SCRIPT, "install")
            .route(InstanceOperations.REINSTALL, null, SCRIPT, "reinstall")
            .route(InstanceOperations.APP_UPDATE, HohenheimMcpAnswers::output, SCRIPT, "update")
            .build();
        McpHostTool deviceSet = McpCodeTool.builder("app_device_set", HohenheimTools.words("mcp_tool_app_device_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_app_device_set"))
            .creates(CatalogRecordWrites.DEVICES.create(), null, List.of(CatalogRecordWrites.APP,
                CatalogRecordWrites.KIND, InstanceDeviceModel.NAME.getInputName()),
                HohenheimTools.words("device_thing"))
            .route(CatalogRecordWrites.DEVICES.change(), HohenheimMcpAnswers::changed)
            .build();
        McpHostTool previewRun = McpCodeTool.single("preview_run", HohenheimTools.words("mcp_tool_preview_run"),
            HohenheimTools.writes(), PreviewParts.RUN, HohenheimCatalogTools::previewQueued);
        McpHostTool runtimeImageSet = McpCodeTool.builder("runtime_image_set",
                HohenheimTools.words("mcp_tool_runtime_image_set"), HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_runtime_image_set"))
            .creates(CatalogRecordWrites.RUNTIME_IMAGES.create(), null, RuntimeImageModel.NAME.getInputName(),
                HohenheimTools.words("runtime_image_thing"))
            .route(CatalogRecordWrites.RUNTIME_IMAGES.change(), HohenheimMcpAnswers::changed)
            .build();
        // A template's code (TP3) changes it, or with approved approves or withdraws it; from_app (AP12) captures a
        // stopped machine as a new one.
        McpHostTool templateSet = McpCodeTool.builder("template_set", HohenheimTools.words("mcp_tool_template_set"),
                HohenheimTools.writes())
            .code(HohenheimTools.words("mcp_code_template_set"))
            .optionalFlag(APPROVED, HohenheimTools.words("mcp_template_approved"))
            .creates(CatalogRecordWrites.TEMPLATES.create(), null, List.of(InstanceTemplateModel.NAME.getInputName(),
                InstanceTemplateModel.KIND.getInputName()), HohenheimTools.words("template_thing"))
            .route(CatalogRecordWrites.TEMPLATES.change(), HohenheimMcpAnswers::changed)
            .route(InstanceTemplateOperations.APPROVE_TEMPLATE, null, APPROVED, "true")
            .route(InstanceTemplateOperations.UNAPPROVE_TEMPLATE, null, APPROVED, "false")
            .routeBy(FROM_APP, HohenheimTools.words("mcp_template_from_app"), InstanceOperations.CAPTURE_TEMPLATE,
                HohenheimCatalogTools::template)
            .build();
        McpHostTool templateImport = McpCodeTool.single("template_import",
            HohenheimTools.words("mcp_tool_template_import"), HohenheimTools.writes(), TemplateDocuments.IMPORT,
            HohenheimCatalogTools::template);
        McpHostTool templateExport = McpCodeTool.builder("template_export",
                HohenheimTools.words("mcp_tool_template_export"), McpHostTool.Annotations.ofReadOnly())
            .code(HohenheimTools.words("mcp_code_template_export"))
            .route(TemplateDocuments.EXPORT, HohenheimCatalogTools::exported)
            .build();
        return List.of(scriptRun, deviceSet, previewRun, runtimeImageSet, templateSet, templateImport,
            templateExport);
    }

    /** A preview builds in the background: its code, read to follow it. */
    private static @NonNull McpResponse previewQueued(@NonNull McpToolContext context, @Nullable Integer preview) {
        String code = preview == null ? null
            : RecordCodes.codeUnjudged(PreviewDeploymentModel.MODEL_ID, String.valueOf(preview));
        return McpResponse.create().line(code == null ? "queued" : "preview: " + code + "; read " + code
            + " to follow its build");
    }

    /** A template a capture or an import made: never approved yet. */
    private static @NonNull McpResponse template(@NonNull McpToolContext context, @Nullable Integer template) {
        String code = template == null ? null
            : RecordCodes.codeUnjudged(InstanceTemplateModel.MODEL_ID, String.valueOf(template));
        return McpResponse.create().line(code == null ? "done" : "template: " + code + ", not approved");
    }

    /** The export document, as written by whoever wrote the template. */
    private static @NonNull McpResponse exported(@NonNull McpToolContext context, @Nullable String document) {
        return McpResponse.create().untrusted("document", document);
    }
}
