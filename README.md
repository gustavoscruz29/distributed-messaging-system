# Distributed Messaging System

**Progressive architecture design in Java/JavaFX.**
From a simple client-server chat to a fully peer-to-peer messaging network.

![Java](https://img.shields.io/badge/-Java-007396?style=flat-square&logo=java&logoColor=white)
![JavaFX](https://img.shields.io/badge/-JavaFX-orange?style=flat-square)
![Sockets](https://img.shields.io/badge/-Sockets-2C3E50?style=flat-square)
![Course](https://img.shields.io/badge/Course-Distributed%20Systems-blue?style=flat-square)

---

## About

This repository contains four progressive implementations of a messaging application, developed for the **Distributed Systems** course. Each stage builds on the previous one, introducing new architectural concepts — starting from a single client-server model and evolving into a fully decentralized peer-to-peer network.

## Repository structure

```
.
├── 01-client-server/               # Simple client-server chat
├── 02-client-server-with-backup/   # Adds a backup mechanism to the server
├── 03-partitioned-servers-with-backup/  # Multiple servers, each handling a group of clients, each with its own backup
└── 04-peer-to-peer/                # Fully peer-to-peer architecture, no central server
```

### Stage 1 — Client-Server
A single server handles all client connections. The baseline implementation: clients connect, send and receive messages through one central point.

### Stage 2 — Client-Server with Backup
Same single-server model, now with a backup mechanism to prevent message/data loss if the main server fails.

### Stage 3 — Partitioned Servers with Backup
The client base is split across multiple servers, each responsible for a specific group of clients — and each of those servers has its own backup, combining horizontal partitioning with fault tolerance.

### Stage 4 — Peer-to-Peer
The architecture moves away from any central server entirely: nodes communicate directly with each other in a peer-to-peer network.

## Why this progression matters

This was the most architecturally complex project developed during the course so far — each stage required rethinking how clients discover each other, how state is kept consistent, and how failures are handled, culminating in a design with no single point of failure.

---

**Course:** Distributed Systems — UESB
