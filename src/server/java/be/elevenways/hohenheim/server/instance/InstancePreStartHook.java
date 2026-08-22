package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * Work a product tier must do BETWEEN container create and start, on every deploy.
 *
 * <p>A deploy recreates the container, which drops every non-primary network
 * attachment, so the tiers that own link networks re-establish them here -- before the
 * workload runs and before any health gate probes it.
 *
 * AIDEV-NOTE: this is a DECLARATION by the owning tier, not an if-chain in
 * {@code InstanceService.deploy}. That method used to call three tiers' static
 * {@code attachLinksBeforeStart} methods by name, and three separate waves had each
 * added exactly one line to it -- the same anti-pattern {@code generatedOnly()} removed
 * from the instance write hook, where {@code release} was hard-coded. A new
 * owning tier now ships one class and is dispatched from the moment it registers.
 *
 * @author Jelle De Loecker
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.instance.InstancePreStartHooks#register")
public interface InstancePreStartHook {

    /** Stable identity, used for ordering ties, dispatch reporting and duplicate refusal. */
    @NonNull Identifier id();

    /**
     * Ascending dispatch position; equal weights tie-break on {@link #id()}.
     *
     * AIDEV-NOTE: deliberately abstract, with no interface default. Discovery order is
     * not an order at all (the registrar is called from generated code, and a map-backed
     * registry answers in hash order -- exactly the defect {@code Panel.landingWeight()}
     * exists to fix in {@code PanelRegistry.all()}). A hook that inherited a weight would
     * be placed by an accident nobody declared; declaring it is one line.
     */
    int weight();

    /**
     * Do the tier's pre-start work, or return immediately when this instance is not the
     * tier's business -- the GATE lives in the implementation, never in the dispatcher.
     *
     * @throws IOException when the work cannot be enforced; the deploy MUST fail rather
     *                     than start a workload whose links were never established
     */
    void beforeStart(InstanceService.@NonNull Resolved resolved, int instanceId) throws IOException;
}
