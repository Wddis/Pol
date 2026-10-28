#!/bin/sh
# stdio bridge: runs a Higgsfield MCP server inside the Windows VM over ssh.
#   mcp-ssh.sh '@higgsfield_org\premiere-mcp'   |   mcp-ssh.sh fnf-after-effects-mcp
exec ssh -T -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -o LogLevel=ERROR -p 2222 Docker@127.0.0.1 "node C:\\npm-global\\node_modules\\$1\\dist\\index.js"
