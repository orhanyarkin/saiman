package io.github.orhanyarkin.saiman.orchestrator.events;

import io.github.orhanyarkin.saiman.shared.run.RunEvent;

/** Published inside the appending transaction; {@link RunEventBus} receives it after the commit. */
record RunEventAppended(RunEvent event) {}
