package srs

import (
	"encoding/binary"
	"io"
	"net/netip"
	"os"

	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/varbin"

	"go4.org/netipx"
)

type myIPSet struct {
	rr []myIPRange
}

type myIPRange struct {
	from netip.Addr
	to   netip.Addr
}

func readIPSet(reader varbin.Reader) (*netipx.IPSet, error) {
	version, err := reader.ReadByte()
	if err != nil {
		return nil, err
	}
	if version != 1 {
		return nil, os.ErrInvalid
	}
	// WTF why using uint64 here
	var length uint64
	err = binary.Read(reader, binary.BigEndian, &length)
	if err != nil {
		return nil, err
	}
	if length > maxSRSItems {
		return nil, os.ErrInvalid
	}
	var builder netipx.IPSetBuilder
	for range length {
		var from, to netip.Addr
		from, err = readIPSetAddr(reader)
		if err != nil {
			return nil, err
		}
		to, err = readIPSetAddr(reader)
		if err != nil {
			return nil, err
		}
		if from.BitLen() != to.BitLen() || from.Compare(to) > 0 {
			return nil, os.ErrInvalid
		}
		builder.AddRange(netipx.IPRangeFrom(from, to))
	}
	return builder.IPSet()
}

func readIPSetAddr(reader varbin.Reader) (netip.Addr, error) {
	addrLen, err := binary.ReadUvarint(reader)
	if err != nil {
		return netip.Addr{}, err
	}
	if addrLen != 4 && addrLen != 16 {
		return netip.Addr{}, os.ErrInvalid
	}
	var addrBytes [16]byte
	_, err = io.ReadFull(reader, addrBytes[:addrLen])
	if err != nil {
		return netip.Addr{}, err
	}
	return M.AddrFromIP(addrBytes[:addrLen]), nil
}

func writeIPSet(writer varbin.Writer, set *netipx.IPSet) error {
	err := writer.WriteByte(1)
	if err != nil {
		return err
	}
	ranges := set.Ranges()
	err = binary.Write(writer, binary.BigEndian, uint64(len(ranges)))
	if err != nil {
		return err
	}
	for _, rr := range ranges {
		fromBytes := rr.From().AsSlice()
		_, err = varbin.WriteUvarint(writer, uint64(len(fromBytes)))
		if err != nil {
			return err
		}
		_, err = writer.Write(fromBytes)
		if err != nil {
			return err
		}
		toBytes := rr.To().AsSlice()
		_, err = varbin.WriteUvarint(writer, uint64(len(toBytes)))
		if err != nil {
			return err
		}
		_, err = writer.Write(toBytes)
		if err != nil {
			return err
		}
	}
	return nil
}
