# Deploy scripts for the VM

Scripts that put OwnTally's server side (the two relays, nginx, the invite site) on the VM and check it. **Read `docs/DEPLOY_VM.md` first**: it says what runs where, the order of steps, how to roll back, and what is not covered.

```
cp deploy/vm/config.env.example deploy/vm/config.env     # then set VM_HOST and VM_USER
deploy/vm/test/run-tests.sh                              # tests of the scripts (no VM needed)
deploy/vm/deploy.sh --only status                        # read-only look at the VM
DRY_RUN=1 deploy/vm/deploy.sh --only ws-relay            # the plan, nothing runs
deploy/vm/deploy.sh --only ws-relay                      # update one part (asks you to type the host name)
deploy/vm/deploy.sh                                      # update everything: ws-relay, iroh-relay, nginx, site
deploy/vm/check.sh                                       # outside checks; SMOKE=1 adds the relay round trip
```

Layout: the top-level scripts run on your machine; `remote/` is copied to the VM and run there as root. `dist/` (build output, downloads) and `config.env` are git-ignored.

Rules the scripts follow: configs are copied from where they live in the repo (no second copy); every replaced file is backed up on the VM first; nginx is reloaded only after `nginx -t` passes; no secret is ever read, printed or stored (there are none: the Iroh token mode is off); nothing is deleted except the scripts' own build folder.

Editing a script: keep it small, run `deploy/vm/test/run-tests.sh`, and update the table in `docs/DEPLOY_VM.md` section 3.
