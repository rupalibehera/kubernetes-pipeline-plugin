/*
 * Copyright (C) 2015 Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.kubernetes.workflow;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.LauncherDecorator;
import hudson.model.Computer;
import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class PodStepExecution extends AbstractStepExecutionImpl {

    @Inject
    private transient PodStep step;

    @StepContextParameter private transient FilePath workspace;
    @StepContextParameter private transient EnvVars env;
    @StepContextParameter private transient TaskListener listener;
    @StepContextParameter private transient Computer computer;
    private transient KubernetesFacade kubernetes;
    private transient String podName;
    private volatile BodyExecution body;

    @Override
    public boolean start() throws Exception {
        StepContext context = getContext();
        podName = step.getName() + "-" + UUID.randomUUID().toString();
        final AtomicBoolean podAlive = new AtomicBoolean(false);
        final CountDownLatch podStarted = new CountDownLatch(1);
        final CountDownLatch podFinished = new CountDownLatch(1);
        kubernetes = new KubernetesFacade();
        Pod pod = kubernetes.createPod(podName, step.getImage(), step.getServiceAccount(), step.getPrivileged(), step.getSecrets(), step.getHostPathMounts(), workspace.getRemote(), createPodEnv(step.getEnv()), "cat");
        kubernetes.watch(podName, podAlive, podStarted, podFinished, true);
        podStarted.await();

        body = context.newBodyInvoker()
                .withContext(BodyInvoker
                .mergeLauncherDecorators(getContext().get(LauncherDecorator.class), new PodExecDecorator(kubernetes, podName, podAlive, podStarted, podFinished)))
                .withCallback(new BodyExecutionCallback() {
                    @Override
                    public void onSuccess(StepContext context, Object result) {
                        listener.error("Done");
                        context.onSuccess(result);
                    }

                    @Override
                    public void onFailure(StepContext context, Throwable t) {
                        listener.error("Failed to execute step:" + t.getMessage());
                        context.onFailure(t);
                    }
                }).start();
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        if (kubernetes != null) {
            kubernetes.deletePod(podName);
            kubernetes.close();
        }
    }

    private List<EnvVar> createPodEnv(Map<String,String> env) throws IOException, InterruptedException {
        List<EnvVar> podEnv = new ArrayList<EnvVar>();
        EnvVars envReduced = new EnvVars(env);
        EnvVars envHost = computer.getEnvironment();
        envReduced.entrySet().removeAll(envHost.entrySet());

        for (Map.Entry<String, String> entry : envReduced.entrySet()) {
            podEnv.add(new EnvVar(entry.getKey(), entry.getValue(), null));
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            podEnv.add(new EnvVar(entry.getKey(), entry.getValue(), null));
        }
        return podEnv;
    }
}
