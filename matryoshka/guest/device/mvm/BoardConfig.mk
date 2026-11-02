# Matryoshka VM guest board: a paravirtual arm64 machine (crosvm / QEMU "virt").
#
# Deliberately simple layout so the host app can boot it with plain kernel + ramdisk + raw disks:
#   vda = system.img (ext4, ro)   vdb = vendor.img (ext4, ro)   vdc = userdata (ext4, formatted on first boot)
# No dynamic partitions, no AVB, no A/B: the host owns integrity (images are SHA-256 checked).

TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_VARIANT := generic
TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic

TARGET_BOARD_PLATFORM := mvm
TARGET_BOOTLOADER_BOARD_NAME := mvm
TARGET_NO_BOOTLOADER := true
TARGET_NO_RECOVERY := true
TARGET_NO_KERNEL := false

# Kernel is built separately (guest/kernel/) and copied in as a prebuilt.
TARGET_PREBUILT_KERNEL := device/matryoshka/mvm/prebuilt/Image
BOARD_KERNEL_CMDLINE := androidboot.hardware=mvm androidboot.selinux=permissive printk.devkmsg=on
BOARD_BOOT_HEADER_VERSION := 2
BOARD_MKBOOTIMG_ARGS := --header_version $(BOARD_BOOT_HEADER_VERSION)
BOARD_KERNEL_PAGESIZE := 4096

# System-as-root with first-stage mount from the ramdisk fstab.
BOARD_BUILD_SYSTEM_ROOT_IMAGE := false
BOARD_USES_RECOVERY_AS_BOOT := false
TARGET_COPY_OUT_VENDOR := vendor
TARGET_COPY_OUT_PRODUCT := system/product
TARGET_COPY_OUT_SYSTEM_EXT := system/system_ext

BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_VENDORIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_USERDATAIMAGE_FILE_SYSTEM_TYPE := ext4
# Sizes leave ~15% headroom for Magisk / microG updates in /data (system stays read-only).
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 2147483648
BOARD_VENDORIMAGE_PARTITION_SIZE := 268435456
BOARD_USERDATAIMAGE_PARTITION_SIZE := 1073741824
BOARD_FLASH_BLOCK_SIZE := 512
TARGET_USERIMAGES_USE_EXT4 := true
TARGET_USERIMAGES_SPARSE_EXT_DISABLED := true

# Flattened APEXes: the container engine has no loop devices, and flattening saves RAM.
OVERRIDE_TARGET_FLATTEN_APEX := true

# Graphics: virtio-gpu + minigbm + drm_hwcomposer; SwiftShader as GLES/Vulkan fallback.
BOARD_GPU_DRIVERS := virgl
TARGET_USES_HWC2 := true
BOARD_USES_MINIGBM := true
BOARD_USES_DRM_HWCOMPOSER := true

BOARD_VENDOR_SEPOLICY_DIRS += device/matryoshka/mvm/sepolicy
DEVICE_MANIFEST_FILE := device/matryoshka/mvm/manifest.xml

# No AVB / dm-verity: images are verified by the host before every boot.
BOARD_AVB_ENABLE := false
