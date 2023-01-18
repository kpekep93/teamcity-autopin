package com.github.ocroquette.teamcity.autopin;

import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

import static com.github.ocroquette.teamcity.autopin.RequestPinningMessageTranslator.TAG_REQUEST_PINNING;
import static com.github.ocroquette.teamcity.autopin.RequestPinningMessageTranslator.TAG_REQUEST_PINNING_INCLUDE_DEPENDENCIES;


public class AutoPinBuildServerListener extends BuildServerAdapter {

    private final org.apache.log4j.Logger LOG = org.apache.log4j.Logger.getLogger(Loggers.SERVER_CATEGORY);
    private final BuildHistory buildHistory;


    public AutoPinBuildServerListener(@NotNull EventDispatcher<BuildServerListener> events,
                                      @NotNull BuildHistory buildHistory) {
        events.addListener(this);
        this.buildHistory = buildHistory;
    }

    @Override
    public void buildFinished(@NotNull SRunningBuild build) {
        final SFinishedBuild finishedBuild = buildHistory.findEntry(build.getBuildId());

        User triggeringUser = build.getTriggeredBy().getUser();

        if (finishedBuild.getTags().contains(TAG_REQUEST_PINNING) || finishedBuild.getTags().contains(TAG_REQUEST_PINNING_INCLUDE_DEPENDENCIES)) {

            String comment = "Pinned automatically based on service message in build #" + finishedBuild.getBuildId();

            finishedBuild.setPinned(true, triggeringUser, comment);

            if (finishedBuild.getTags().contains(TAG_REQUEST_PINNING_INCLUDE_DEPENDENCIES)) {
                List<? extends BuildPromotion> allDependencies = finishedBuild.getBuildPromotion().getAllDependencies();

                for (BuildPromotion bp : allDependencies) {
                    buildHistory.findEntry(bp.getAssociatedBuild().getBuildId()).setPinned(true, triggeringUser, comment);
                }
            }

            BuildTagHelper.removeTag(finishedBuild, TAG_REQUEST_PINNING);
            BuildTagHelper.removeTag(finishedBuild, TAG_REQUEST_PINNING_INCLUDE_DEPENDENCIES);
        }

        for (SBuildFeatureDescriptor bfd : finishedBuild.getBuildFeaturesOfType(AutoPinBuildFeature.TYPE)) {
            if (arePinningConditionsMet(bfd.getParameters(), finishedBuild)) {
                String comment = bfd.getParameters().get(AutoPinBuildFeature.PARAM_COMMENT);
                finishedBuild.setPinned(true, triggeringUser, comment);

                if (StringUtils.isTrue(bfd.getParameters().get(AutoPinBuildFeature.PARAM_PIN_DEPENDENCIES))) {
                    for (BuildPromotion bp : finishedBuild.getBuildPromotion().getAllDependencies()) {
                        buildHistory.findEntry(bp.getAssociatedBuild().getBuildId()).setPinned(true, triggeringUser, comment);
                    }
                }

                // If checked, unpin previous build
                if (StringUtils.isTrue(bfd.getParameters().get(AutoPinBuildFeature.PARAM_UNPIN_PREVIOUS))){
                    SFinishedBuild previousPinnedBuild = getPreviousPinned(finishedBuild);
                    
                    // Unpin previous pinned build from current build configuration
                    if (previousPinnedBuild != null && previousPinnedBuild.getBuildTypeId() == finishedBuild.getBuildTypeId()){
                        previousPinnedBuild.setPinned(false, triggeringUser, comment);
                    }
                }

                // If checked, unpin other pinned builds
                if (StringUtils.isTrue(bfd.getParameters().get(AutoPinBuildFeature.PARAM_UNPIN_OTHERS))) {
                    SFinishedBuild previousPinnedBuild = getPreviousPinned(finishedBuild);
                    
                    while (previousPinnedBuild != null){
                        // Unpin previous pinned build from current build configuration
                        if (previousPinnedBuild.getBuildTypeId() == finishedBuild.getBuildTypeId()){
                            previousPinnedBuild.setPinned(false, triggeringUser, comment);
                        }

                        // And get previous pinned build
                        previousPinnedBuild = getPreviousPinned(previousPinnedBuild);
                    }
                }

                String tag = bfd.getParameters().get(AutoPinBuildFeature.PARAM_TAG);

                if (StringUtils.isSet(tag)) {
                    BuildTagHelper.addTag(finishedBuild, tag);

                    if (StringUtils.isTrue(bfd.getParameters().get(AutoPinBuildFeature.PARAM_PIN_DEPENDENCIES))) {
                        for (BuildPromotion bp : finishedBuild.getBuildPromotion().getAllDependencies()) {
                            BuildTagHelper.addTag(buildHistory.findEntry(bp.getAssociatedBuild().getBuildId()), tag);
                        }
                    }
                }
            }
        }
    }

    private boolean arePinningConditionsMet(Map<String, String> parameters, SBuild build) {
        boolean matching = true;

        String requestedStatus = parameters.get(AutoPinBuildFeature.PARAM_STATUS);
        if (requestedStatus != null) {
            if (requestedStatus.equals(AutoPinBuildFeature.PARAM_STATUS_SUCCESSFUL)) {
                matching = matching && build.getBuildStatus().equals(Status.NORMAL);
            } else if (requestedStatus.equals(AutoPinBuildFeature.PARAM_STATUS_FAILED)) {
                matching = matching && !build.getBuildStatus().equals(Status.NORMAL);
            }
        }

        String requestedBranchPattern = parameters.get(AutoPinBuildFeature.PARAM_BRANCH_PATTERN);
        if (requestedBranchPattern != null && !requestedBranchPattern.isEmpty() && build.getBranch() != null) {
            matching = matching && build.getBranch().getDisplayName().matches(requestedBranchPattern);
        }

        return matching;
    }

    private SFinishedBuild getPreviousPinned(SFinishedBuild finishedBuild){
        // Get previous build
        SFinishedBuild previousPinnedBuild = finishedBuild.getPreviousFinished();
        
        // If this build is not pinned
        if (previousPinnedBuild != null && !previousPinnedBuild.isPinned()){
            // Get previous build
            previousPinnedBuild = getPreviousPinned(previousPinnedBuild);
        }

        // Return previous pinned build
        return previousPinnedBuild;
    }
}
