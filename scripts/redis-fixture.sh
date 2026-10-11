#!/bin/sh
set -eu
mkdir -p /tmp/clover-redis
printf '%s\n' 'user default off' 'user clover on >test-only-password ~* &cloverchat:test:* +@all' > /tmp/clover-redis/users.acl
redis-server --port 16379 --bind 0.0.0.0 --protected-mode no --save '' --appendonly no --aclfile /tmp/clover-redis/users.acl --daemonize yes
redis-server --port 0 --tls-port 16443 --bind 0.0.0.0 --protected-mode no --save '' --appendonly no --aclfile /tmp/clover-redis/users.acl --tls-cert-file /fixture/redis.crt --tls-key-file /fixture/redis.key --tls-ca-cert-file /fixture/redis.crt --tls-auth-clients no --daemonize yes
cat > /tmp/clover-redis/sentinel.conf <<EOF
port 26389
bind 0.0.0.0
protected-mode no
sentinel monitor clover-master 127.0.0.1 16379 1
sentinel auth-user clover-master clover
sentinel auth-pass clover-master test-only-password
user default off
user sentinel-user on >sentinel-only-password ~* &* +@all
EOF
redis-server /tmp/clover-redis/sentinel.conf --sentinel --daemonize yes
for port in 17000 17001 17002; do
  mkdir -p "/tmp/clover-redis/$port"
  redis-server --port "$port" --bind 0.0.0.0 --protected-mode no --save '' --appendonly no --cluster-enabled yes --cluster-config-file "/tmp/clover-redis/$port/nodes.conf" --cluster-node-timeout 3000 --cluster-announce-ip 127.0.0.1 --aclfile /tmp/clover-redis/users.acl --daemonize yes
done
for port in 16379 17000 17001 17002; do
  for attempt in $(seq 1 30); do
    if redis-cli -p "$port" --user clover --pass test-only-password --no-auth-warning ping >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  redis-cli -p "$port" --user clover --pass test-only-password --no-auth-warning ping
done
redis-cli --user clover --pass test-only-password --no-auth-warning --cluster create 127.0.0.1:17000 127.0.0.1:17001 127.0.0.1:17002 --cluster-replicas 0 --cluster-yes
for attempt in $(seq 1 30); do
  if redis-cli -p 17000 --user clover --pass test-only-password --no-auth-warning cluster info | grep -q 'cluster_state:ok'; then
    break
  fi
  sleep 1
done
redis-cli -p 17000 --user clover --pass test-only-password --no-auth-warning cluster info | grep -q 'cluster_state:ok'
touch /tmp/clover-redis/ready
exec tail -f /dev/null
