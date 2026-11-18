# Algorithms Explained (Simple Version)

A plain-language summary of three things about GoEV's routing system: the path
optimization algorithm, the EVCS participation factor, and the routing
(assignment) algorithm. Written for explaining the project out loud, not as a
technical reference — see `docs/ev-evcs-architecture-design.md` for the full
design.

## 1. Path optimization algorithm

Finding the electrical path between an EV and a charging station.

- The distribution network is modeled as a **tree** (like a family tree of
  buses/wires), not a general road map with many possible routes.
- In a tree, there is only **one possible path** between any two points.
- The Java backend uses JGraphT **Dijkstra** to calculate its weighted length.
- The dashboard reconstructs that unique path by walking ancestors, similar
  to the **Lowest Common Ancestor (LCA)** method. This is frontend path drawing,
  not the backend routing algorithm.

## 2. EVCS participation factor

How much a charging station "counts" toward a decision.

Each EVCS gets a **score** made of several weighted factors:

- **Distance** — closer stations score higher.
- **Urgency** — EVs with lower battery (SOC) get priority for nearby
  stations.
- **Queue length** — stations with fewer EVs already waiting score higher.
- **Line headroom** — how much spare electrical capacity is left on the line
  feeding that station (a station near an already-overloaded line scores
  lower).

Live charging load at each station is subtracted from the capacity of every
line upstream of it. So one EVCS actively drawing power reduces how
"available" it looks to other requests sharing that same line — that's the
"participation": its real-time load participates in shrinking the network's
remaining capacity for everyone else on that branch.

## 3. Routing / assignment algorithm

Deciding which EV goes to which station.

Two modes, for two situations:

- **Real-time (greedy)** — when one request comes in, immediately pick the
  single best-scoring available station using the formula above. Fast, but
  decides one request at a time.
- **Batch (optimizer)** — when multiple requests arrive close together,
  instead of deciding one-by-one, a solver (**Timefold**, a constraint-based
  optimization engine) looks at *all* pending requests together and finds the
  best overall assignment — making sure no station is over-booked (hard
  rule) while maximizing total score across everyone (soft goal).

In short: greedy = fast and simple for one request; batch optimizer =
smarter, considers everyone together, avoids conflicts.
