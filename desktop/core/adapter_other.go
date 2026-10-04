//go:build !windows

package main

func removeStaleAdapter(string) (int, error) {
	return 0, nil
}
