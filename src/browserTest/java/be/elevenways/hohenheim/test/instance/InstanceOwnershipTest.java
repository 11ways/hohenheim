package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The instance tier's tenancy predicate: sameOwner over the manage-grant subject
 * sets, generalized off sites -- the check that would be security theater if the
 * capability vocabulary were not registered (empty set == empty set for every pair).
 */
class InstanceOwnershipTest extends HohenheimTestBase {

    private final List<Row> createdInstances = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : this.createdInstances) {
            if (row.get(InstanceModel.ID) != null) {
                instances.delete(row);
            }
        }
        this.createdInstances.clear();
    }

    private Row instance(String name) {
        Model model = Models.get(InstanceModel.class);
        Row row = model.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        model.save(row);
        this.createdInstances.add(row);
        return row;
    }

    private static int tenant(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    @Test
    void instanceOwnershipIsGrantDerivedAndTheCheckCanActuallyFail() {
        // 1. Two operator-owned instances: no grants at all, so both carry the EMPTY
        //    subject set and compare same-owner -- the documented, deliberate semantic.
        Row first = instance("owner-a");
        Row second = instance("owner-b");
        int firstId = first.get(InstanceModel.ID);
        int secondId = second.get(InstanceModel.ID);
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, firstId))
            .as("step 1: an ungranted instance is operator-owned (empty set, not an error)")
            .isEmpty();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, firstId, secondId))
            .as("step 1: two operator-owned instances compare same-owner")
            .isTrue();

        // 2. Tenant A takes the first instance: the subject sets now differ, so the SAME
        //    pair flips to different owners. THIS assertion is the counterfactual anchor:
        //    without the KnownCapabilities/declareGrantable registration for
        //    InstanceModel, the grant cannot exist, both sets stay empty, and this check
        //    CANNOT fail -- exactly the security theater the plan names.
        int tenantA = tenant("instance-tenant-a@test");
        RecordGrants.grant(GrantSubjectType.USER, tenantA, InstanceModel.MODEL_ID, firstId,
            HohenheimAccess.MANAGE, true);
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, firstId))
            .as("step 2: the manage-grant subject IS the owner identity")
            .containsExactly("user:" + tenantA);
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, firstId, secondId))
            .as("step 2: a tenant-held instance and an operator-owned one differ in owner")
            .isFalse();

        // 3. A second tenant on the second instance: still different owners (equality of
        //    sets, never overlap).
        int tenantB = tenant("instance-tenant-b@test");
        RecordGrants.grant(GrantSubjectType.USER, tenantB, InstanceModel.MODEL_ID, secondId,
            HohenheimAccess.MANAGE, true);
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, firstId, secondId))
            .as("step 3: two differently-tenanted instances are NOT same-owner")
            .isFalse();

        // 4. A third instance held by tenant A compares same-owner with the first: the
        //    predicate answers from the sets, not the record ids.
        Row third = instance("owner-c");
        int thirdId = third.get(InstanceModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, tenantA, InstanceModel.MODEL_ID, thirdId,
            HohenheimAccess.MANAGE, true);
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, firstId, thirdId))
            .as("step 4: two instances of ONE tenant are same-owner")
            .isTrue();

        // 5. {A} versus {A, B} is NOT same-owner: overlap would let B seize what A serves.
        RecordGrants.grant(GrantSubjectType.USER, tenantB, InstanceModel.MODEL_ID, thirdId,
            HohenheimAccess.MANAGE, true);
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, firstId, thirdId))
            .as("step 5: subset ownership is different ownership (equality, not overlap)")
            .isFalse();

        // 6. The liveWhen predicate: a soft-deleted instance refuses NEW grants -- a
        //    trashed record must not accumulate authority that comes back on restore.
        Model instances = Models.get(InstanceModel.class);
        second.set(InstanceModel.DELETED_AT, Instant.now());
        instances.save(second);
        assertThat(catchThrowable(() -> RecordGrants.grant(GrantSubjectType.USER, tenantA,
                InstanceModel.MODEL_ID, secondId, HohenheimAccess.MANAGE, true)))
            .as("step 6: planting a grant on a soft-deleted instance is refused")
            .isNotNull();

        // 7. The site spelling still rides the SAME derivation: the two-int convenience
        //    delegates instead of keeping a second copy of the subject-set logic.
        assertThat(HohenheimAccess.sameOwner(firstId, firstId))
            .as("step 7: the site-flavoured overload still answers (identity fold)")
            .isTrue();
    }
}
