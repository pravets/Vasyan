# CodeRabbit Fix Report

## Changes

- Added shared BlockEntity protection to `DigRules.isSafeToDig`; chest regression coverage is in `DigRulesTest`.
- DIG destinations now reject known supports deeper than `NAV_MAX_DROP_DOWN` while retaining ordinary passable destinations.
- DIG pricing includes the foot block and only a breakable head block; passable heads add no cost.
- P1 recovery scaffold selection now consistently applies `NAV_SCAFFOLD_WHITELIST`.
- Placement revalidation requires both replaceable/liquid-compatible destination and adjacent solid support.
- `VasyanPath` explicitly rejects inherited node mutations to preserve immutable transition metadata.
- Added public API JavaDoc where touched and marked the original P2 plan superseded by the repair plan and edge-aware architecture.

## Verification

- Focused navigation tests: passed.
- `./gradlew compileJava compileTestJava`: passed.
- Full `./gradlew test`: passed, 394 tests completed with 0 failures.
- `python -m py_compile scripts/behavior/behavior_test.py`: passed.
- Behavior server was unavailable locally; no behavior run was fabricated.

## Decisions and deviations

- The approved P2 design intentionally uses virtual tools and hardness-based cost. Real inventory/tool simulation was not implemented.
- The evaluator treats unbounded fixture/void space as unknown rather than as a measured deep shaft; only a known deep support is rejected.
