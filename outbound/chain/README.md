# Chain outbound (kernel contract)

This directory is documentation only. The Android app does **not** compile
this Go code into libbox.

The real implementation lives in the kernel fork:

- repo: https://github.com/dukangalex/sing-box
- branch: `chain-dev`
- files: `option/chain.go`, `option/chain_compile.go`, `protocol/chain/outbound.go`

## JSON the app must emit

```json
{
  "type": "chain",
  "tag": "chainbox-chain-1-1",
  "outbounds": ["entry-hop", "landing-hop"]
}
```

That is the entire public schema (`option.ChainOutboundOptions`). The kernel
compiler clones intermediate hops, sets `detour` internally, skips
direct/block/dns members of selector/urltest groups, and fail-closes on
missing hops.

Do **not** add extra JSON fields. Unknown fields (historically `fail_closed`)
make sing-box refuse to decode the config.

See `docs/CHAIN.md` in the kernel repository.
