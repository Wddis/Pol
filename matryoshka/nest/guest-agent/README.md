# Nest guest agent

A small Java program that runs **inside the guest** (Android 11 under the Nest engine) as an init
service with the system uid (`app_process`, see `nest_run.cpp`, `--net-agent`).

* `NetAgent` gives the guest a network: the guest's sockets are the phone's own sockets, so the
  guest is always online, but its ConnectivityService knows no network (there is no interface to
  configure). The agent registers one Ethernet-type network (NetworkAgent) with the phone's DNS
  servers, so apps see "connected" and DNS resolution works.

`src/` is packaged into `nest-agent.jar` (dex) by the app's Gradle build (`buildGuestAgent`).
`stubs/` only declares the framework classes the agent compiles against (the guest provides the
real ones); it is never packaged.

SPDX-License-Identifier: Apache-2.0
