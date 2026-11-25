#ifndef ODA_UDP_POLICY_H
#define ODA_UDP_POLICY_H

#include <string.h>
#include <lwip/ip4.h>
#include <lwip/ip6.h>
#include <lwip/inet_chksum.h>
#include <lwip/pbuf.h>

struct _HevMappedDNS;
extern struct _HevMappedDNS *hev_mapped_dns_get(void);

/* Psiphon and Tor use mapped DNS and offer a TCP-only SOCKS listener.
 * Reply to QUIC datagrams in the virtual interface so clients can fall back to TCP. */
static inline int
oda_reject_quic(struct pbuf *packet, struct netif *input, int ipv6)
{
    unsigned char header[48];
    const unsigned int ip_header = ipv6 ? 40 : 20;
    const unsigned int quoted = ip_header + 8;

    if (pbuf_copy_partial(packet, header, quoted, 0) != quoted ||
        (ipv6 ? ((header[0] >> 4) != 6 || header[6] != 17 || header[8] == 255 ||
                 header[24] == 255)
              : (header[0] != 0x45 || header[9] != 17 || (header[6] & 0x3f) ||
                 header[7] || header[12] >= 224 || (header[16] & 0xf0) == 0xe0 ||
                 (header[16] == 255 && header[17] == 255 && header[18] == 255 && header[19] == 255))) ||
        header[ip_header + 2] != 0x01 || header[ip_header + 3] != 0xbb ||
        hev_mapped_dns_get() == NULL)
        return 0;

    struct pbuf *reply = pbuf_alloc(PBUF_IP, 8 + quoted, PBUF_RAM);
    if (reply == NULL) return 0;
    unsigned char *icmp = reply->payload;
    memset(icmp, 0, 8);
    icmp[0] = ipv6 ? 1 : 3;
    icmp[1] = ipv6 ? 4 : 3;
    memcpy(icmp + 8, header, quoted);

    if (ipv6) {
        ip6_addr_t src = {0}, dst = {0};
        memcpy(src.addr, header + 24, 16);
        memcpy(dst.addr, header + 8, 16);
        u16_t checksum = ip6_chksum_pseudo(reply, 58, reply->tot_len, &src, &dst);
        memcpy(icmp + 2, &checksum, sizeof(checksum));
        ip6_output_if(reply, &src, &dst, 64, 0, 58, input);
    } else {
        ip4_addr_t src, dst;
        memcpy(&src.addr, header + 16, 4);
        memcpy(&dst.addr, header + 12, 4);
        u16_t checksum = inet_chksum(icmp, reply->len);
        memcpy(icmp + 2, &checksum, sizeof(checksum));
        ip4_output_if(reply, &src, &dst, 64, 0, 1, input);
    }
    pbuf_free(reply);
    pbuf_free(packet);
    return 1;
}

#define LWIP_HOOK_IP4_INPUT(p, inp) oda_reject_quic(p, inp, 0)
#define LWIP_HOOK_IP6_INPUT(p, inp) oda_reject_quic(p, inp, 1)
#endif
