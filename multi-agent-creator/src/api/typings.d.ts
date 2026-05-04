declare namespace API {
  // Base Response Types
  type BaseResponse<T> = {
    code?: number;
    data?: T;
    message?: string;
  };

  type BaseResponseString = BaseResponse<string>;
  type BaseResponseBoolean = BaseResponse<boolean>;
  type BaseResponseLong = BaseResponse<number>;
  type BaseResponseUser = BaseResponse<User>;
  type BaseResponseLoginUserVO = BaseResponse<LoginUserVO>;
  type BaseResponseUserVO = BaseResponse<UserVO>;
  type BaseResponsePageUserVO = BaseResponse<PageUserVO>;

  // Request Types
  type DeleteRequest = {
    id?: number;
  };

  type PageRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
  };

  type UserLoginRequest = {
    userAccount?: string;
    userPassword?: string;
  };

  type UserRegisterRequest = {
    userAccount?: string;
    userPassword?: string;
    checkPassword?: string;
  };

  type UserAddRequest = {
    userName?: string;
    userAccount?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserUpdateRequest = {
    id?: number;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserQueryRequest = PageRequest & {
    id?: number;
    userName?: string;
    userAccount?: string;
    userProfile?: string;
    userRole?: string;
  };

  // Response Data Types
  type User = {
    id?: number;
    userAccount?: string;
    userPassword?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    quota?: number;
    vipTime?: string;
    editTime?: string;
    createTime?: string;
    updateTime?: string;
    isDelete?: number;
  };

  type LoginUserVO = {
    id?: number;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
    updateTime?: string;
  };

  type UserVO = {
    id?: number;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
  };

  type PageUserVO = {
    records?: UserVO[];
    total?: number;
    size?: number;
    current?: number;
    pages?: number;
  };

  // Parameter Types
  type getUserByIdParams = {
    id?: string;
  };

  type getUserVOByIdParams = {
    id?: string;
  };
}
