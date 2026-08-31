package main

import (
	"net"
	"strings"
)

func netInterfaceAddrs() ([]string, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var out []string
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			s := a.String()
			if idx := strings.Index(s, "/"); idx > 0 {
				s = s[:idx]
			}
			if ip := net.ParseIP(s); ip != nil && ip.To4() != nil {
				out = append(out, s)
			}
		}
	}
	return out, nil
}
